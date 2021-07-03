/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.rpc.netty

import java.util.concurrent.{ConcurrentHashMap, ConcurrentMap, LinkedBlockingQueue, ThreadPoolExecutor, TimeUnit}
import javax.annotation.concurrent.GuardedBy

import scala.collection.JavaConverters._
import scala.concurrent.Promise
import scala.util.control.NonFatal

import org.apache.spark.SparkException
import org.apache.spark.internal.Logging
import org.apache.spark.network.client.RpcResponseCallback
import org.apache.spark.rpc._
import org.apache.spark.util.ThreadUtils

/**
 * A message dispatcher, responsible for routing RPC messages to the appropriate endpoint(s).
 *
 * @param numUsableCores Number of CPU cores allocated to the process, for sizing the thread pool.
 *                       If 0, will consider the available CPUs on the host.
 */
private[netty] class Dispatcher(nettyEnv: NettyRpcEnv, numUsableCores: Int) extends Logging {

  /**
   * RPC端点数据。
   * Inbox与RpcEndpoint、NettyRpcEndpointRef通过EndpointData相关联。
   * @param name
   * @param endpoint
   * @param ref
   */
  private class EndpointData(
      val name: String,
      val endpoint: RpcEndpoint,
      val ref: NettyRpcEndpointRef) {
    val inbox = new Inbox(ref, endpoint)
  }

  /*
  端点实例名称与端点数据EndpointData之间映射关系的缓存。
   */
  private val endpoints: ConcurrentMap[String, EndpointData] =
    new ConcurrentHashMap[String, EndpointData]
  /*
  RpcEndpointRef:RPC端点引用，所谓引用实际是"spark://host:port/name"
  host:端点所在RPC服务所在的主机IP
  port:端点所在RPC服务的端口
  name:端点实例的名称
   */
  private val endpointRefs: ConcurrentMap[RpcEndpoint, RpcEndpointRef] =
    new ConcurrentHashMap[RpcEndpoint, RpcEndpointRef]

  // Track the receivers whose inboxes may contain messages.
  // 存储端点数据EndpointData的阻塞队列。只有Inbox中有消息的EndpointData才会被放入阻塞队列。
  private val receivers = new LinkedBlockingQueue[EndpointData]

  /**
   * True if the dispatcher has been stopped. Once stopped, all messages posted will be bounced
   * immediately.
   *
   * Dispatcher是否停止的状态。
   */
  @GuardedBy("this")
  private var stopped = false

  /**
   * 注册RpcEndpoint。
   * 这个方法的副作用是将EndpointData放入receivers。
   * @param name
   * @param endpoint
   * @return
   */
  def registerRpcEndpoint(name: String, endpoint: RpcEndpoint): NettyRpcEndpointRef = {
    // 使用当前RpcEndpoint所在NettyRpcEnv的地址和RpcEndpoint的名称创建RpcEndpointAddress对象。
    val addr = RpcEndpointAddress(nettyEnv.address, name)
    // 创建RpcEndpoint的引用对象
    val endpointRef = new NettyRpcEndpointRef(nettyEnv.conf, addr, nettyEnv)
    synchronized {
      if (stopped) {
        throw new IllegalStateException("RpcEnv has been stopped")
      }
      // 创建EndpointData，并放入endpoints缓存。
      if (endpoints.putIfAbsent(name, new EndpointData(name, endpoint, endpointRef)) != null) {
        throw new IllegalArgumentException(s"There is already an RpcEndpoint called $name")
      }
      val data = endpoints.get(name)
      endpointRefs.put(data.endpoint, data.ref)
      receivers.offer(data)  // for the OnStart message
    }
    endpointRef
  }

  def getRpcEndpointRef(endpoint: RpcEndpoint): RpcEndpointRef = endpointRefs.get(endpoint)

  def removeRpcEndpointRef(endpoint: RpcEndpoint): Unit = endpointRefs.remove(endpoint)

  // Should be idempotent
  private def unregisterRpcEndpoint(name: String): Unit = {
    // 从endpoints中移除EndpointData
    val data = endpoints.remove(name)
    if (data != null) {
      //调用EndpointData中Inbox的stop方法停止Inbox。
      data.inbox.stop()
      // 将EndpointDdata重新放入receivers中。
      receivers.offer(data)  // for the OnStop message
    }
    // Don't clean `endpointRefs` here because it's possible that some messages are being processed
    // now and they can use `getRpcEndpointRef`. So `endpointRefs` will be cleaned in Inbox via
    // `removeRpcEndpointRef`.
    /*
    当要移除一个EndpointData时，其Inbox可能正在对消息进行处理，所以不能贸然停止。
    这里采用了更平滑的停止方式，即调用了Inbox的stop方法来平滑过渡。
     */
  }

  def stop(rpcEndpointRef: RpcEndpointRef): Unit = {
    synchronized {
      // 判断Dispatcher是否已经停止。
      if (stopped) {
        // This endpoint will be stopped by Dispatcher.stop() method.
        return
      }
      // 对RpcEndpoint去注册
      unregisterRpcEndpoint(rpcEndpointRef.name)
    }
  }

  /**
   * Send a message to all registered [[RpcEndpoint]]s in this process.
   *
   * This can be used to make network events known to all end points (e.g. "a new node connected").
   *
   * InboxMessage：Inbox盒子内的消息。Inboxmessage是一个特质，所有类型的RPC消息都继承自InboxMessage。
   */
  def postToAll(message: InboxMessage): Unit = {
    val iter = endpoints.keySet().iterator()
    while (iter.hasNext) {
      val name = iter.next
        postMessage(name, message, (e) => { e match {
          case e: RpcEnvStoppedException => logDebug (s"Message $message dropped. ${e.getMessage}")
          case e: Throwable => logWarning(s"Message $message dropped. ${e.getMessage}")
        }}
      )}
  }

  /** Posts a message sent by a remote endpoint. */
  def postRemoteMessage(message: RequestMessage, callback: RpcResponseCallback): Unit = {
    val rpcCallContext =
      new RemoteNettyRpcCallContext(nettyEnv, callback, message.senderAddress)
    val rpcMessage = RpcMessage(message.senderAddress, message.content, rpcCallContext)
    postMessage(message.receiver.name, rpcMessage, (e) => callback.onFailure(e))
  }

  /** Posts a message sent by a local endpoint. */
  def postLocalMessage(message: RequestMessage, p: Promise[Any]): Unit = {
    val rpcCallContext =
      new LocalNettyRpcCallContext(message.senderAddress, p)
    val rpcMessage = RpcMessage(message.senderAddress, message.content, rpcCallContext)
    postMessage(message.receiver.name, rpcMessage, (e) => p.tryFailure(e))
  }

  /** Posts a one-way message. */
  def postOneWayMessage(message: RequestMessage): Unit = {
    postMessage(message.receiver.name, OneWayMessage(message.senderAddress, message.content),
      (e) => throw e)
  }

  /**
   * Posts a message to a specific endpoint.
   *
   * @param endpointName name of the endpoint.
   * @param message the message to post
   * @param callbackIfStopped callback function if the endpoint is stopped.
   */
  private def postMessage(
      endpointName: String,
      message: InboxMessage,
      callbackIfStopped: (Exception) => Unit): Unit = {
    val error = synchronized {
      // 获取EndpointData
      val data = endpoints.get(endpointName)
      if (stopped) {
        Some(new RpcEnvStoppedException())
      } else if (data == null) {
        Some(new SparkException(s"Could not find $endpointName."))
      } else { //Dispatcher没有停止并且缓存endpoints中确实存在名为endpointName的EndpointData
        data.inbox.post(message)
        receivers.offer(data)
        None
      }
    }
    // We don't need to call `onStop` in the `synchronized` block
    error.foreach(callbackIfStopped)
  }

  def stop(): Unit = {
    synchronized {
      if (stopped) {
        return
      }
      // 如果Dispatcher还未停止，则将自身状态修改为已停止。
      stopped = true
    }
    // Stop all endpoints. This will queue all endpoints for processing by the message loops.
    // 对endpoints中的所有EndpointData去注册。
    // 向endpoints中的每个EndpointData的Inbox里放置OnStop消息。
    endpoints.keySet().asScala.foreach(unregisterRpcEndpoint)
    // Enqueue a message that tells the message loops to stop.
    // 向receivers中投放"毒药"
    receivers.offer(PoisonPill)
    // 由于Dispatcher都停止了，所以Dispatcher中的所有MessageLoop线程也就没有存在的必要了。
    threadpool.shutdown()
  }

  def awaitTermination(): Unit = {
    threadpool.awaitTermination(Long.MaxValue, TimeUnit.MILLISECONDS)
  }

  /**
   * Return if the endpoint exists
   */
  def verify(name: String): Boolean = {
    endpoints.containsKey(name)
  }

  /** Thread pool used for dispatching messages.
   * 用于对消息进行调度的线程池。此线程池运行的任务都是MessageLoop。
   * */
  private val threadpool: ThreadPoolExecutor = {
    val availableCores =
      if (numUsableCores > 0) numUsableCores else Runtime.getRuntime.availableProcessors()
    // 获取此线程池的大小
    val numThreads = nettyEnv.conf.getInt("spark.rpc.netty.dispatcher.numThreads",
      math.max(2, availableCores))
    // 创建线程池
    val pool = ThreadUtils.newDaemonFixedThreadPool(numThreads, "dispatcher-event-loop")
    for (i <- 0 until numThreads) {
      pool.execute(new MessageLoop)
    }
    pool
  }

  /** Message loop used for dispatching messages. */
  private class MessageLoop extends Runnable {
    override def run(): Unit = {
      try {
        while (true) {
          try {
            /*
            从receivers中获取EndpointData。
            只有Inbox的messages列表中有了新的消息，此EndpointData才会被放入receivers中。
            由于receivers是个阻塞队列，所以当receivers中没有EndpointData时，MessageLoop线程会被阻塞。
             */
            val data = receivers.take()
            if (data == PoisonPill) {
              // Put PoisonPill back so that other MessageLoops can see it.
              receivers.offer(PoisonPill)
              return
            }
            // 对消息进行处理。
            data.inbox.process(Dispatcher.this)
          } catch {
            case NonFatal(e) => logError(e.getMessage, e)
          }
        }
      } catch {
        case _: InterruptedException => // exit
        case t: Throwable =>
          try {
            // Re-submit a MessageLoop so that Dispatcher will still work if
            // UncaughtExceptionHandler decides to not kill JVM.
            threadpool.execute(new MessageLoop)
          } finally {
            throw t
          }
      }
    }
  }

  /** A poison endpoint that indicates MessageLoop should exit its message loop. */
  private val PoisonPill = new EndpointData(null, null, null)
}
