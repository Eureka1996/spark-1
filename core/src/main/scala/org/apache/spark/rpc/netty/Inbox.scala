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

import javax.annotation.concurrent.GuardedBy

import scala.util.control.NonFatal

import org.apache.spark.SparkException
import org.apache.spark.internal.Logging
import org.apache.spark.rpc.{RpcAddress, RpcEndpoint, ThreadSafeRpcEndpoint}


private[netty] sealed trait InboxMessage

/**
 * RpcEndpoint处理此类型的消息后不需要向客户端回复信息。
 * @param senderAddress
 * @param content
 */
private[netty] case class OneWayMessage(
    senderAddress: RpcAddress,
    content: Any) extends InboxMessage

/**
 * RPC消息，RpcEndpoint处理完此消息后需要向客户端回复信息。
 * @param senderAddress
 * @param content
 * @param context
 */
private[netty] case class RpcMessage(
    senderAddress: RpcAddress,
    content: Any,
    context: NettyRpcCallContext) extends InboxMessage

/**
 * 用于Inbox实例化后，再通知与此Inbox相关联的RpcEndpoint启动。
 */
private[netty] case object OnStart extends InboxMessage

/**
 * 用于Inbox停止后，通知与此Inbox相关联的RpcEndpoint停止。
 */
private[netty] case object OnStop extends InboxMessage

/** A message to tell all endpoints that a remote process has connected.
 * 此消息用于告诉所有的RpcEndpoint，有远端的进程已经与当前RPC服务建立了连接。
 * */
private[netty] case class RemoteProcessConnected(remoteAddress: RpcAddress) extends InboxMessage

/** A message to tell all endpoints that a remote process has disconnected. */
private[netty] case class RemoteProcessDisconnected(remoteAddress: RpcAddress) extends InboxMessage

/** A message to tell all endpoints that a network error has happened.
 * 此消息用于告诉所有的RpcEndpoint，与远端某个地址之间的连接发生了错误。
 * */
private[netty] case class RemoteProcessConnectionError(cause: Throwable, remoteAddress: RpcAddress)
  extends InboxMessage

/**
 * An inbox that stores messages for an [[RpcEndpoint]] and posts messages to it thread-safely.
 *
 * 端点内的盒子，每个RpcEndpoint都有一个对应的盒子，这个盒子里有个存储InboxMessage消息的列表messages.
 * 所有的消息将缓存在messages列表里面，并由RpcEndpoint异步处理这些消息。
 */
private[netty] class Inbox(
    val endpointRef: NettyRpcEndpointRef,
    val endpoint: RpcEndpoint)
  extends Logging {

  inbox =>  // Give this an alias so we can use it more clearly in closures.

  @GuardedBy("this")
  protected val messages = new java.util.LinkedList[InboxMessage]()

  /** True if the inbox (and its associated endpoint) is stopped.
   * Inbox的停止状态。
   * */
  @GuardedBy("this")
  private var stopped = false

  /** Allow multiple threads to process messages at the same time.
   * 是否允许多个线程同时处理message中的消息。
   * */
  @GuardedBy("this")
  private var enableConcurrent = false

  /** The number of threads processing messages for this inbox.
   * 激活线程的数量，即正在处理messages中消息的线程数量。
   * */
  @GuardedBy("this")
  private var numActiveThreads = 0

  // OnStart should be the first message to process
  inbox.synchronized {
    messages.add(OnStart)
  }

  /**
   * Process stored messages.
   */
  def process(dispatcher: Dispatcher): Unit = {
    var message: InboxMessage = null
    inbox.synchronized {
      /*
     进行线程并发检查。
     如果不允许多个线程同时处理messages中的消息，并且当前激活线程数不为0，
     说明已经有线程在处理消息，所以当前线程不允许再去处理消息。
     */
      if (!enableConcurrent && numActiveThreads != 0) {
        return
      }
      message = messages.poll()
      if (message != null) {
        numActiveThreads += 1
      } else {
        return
      }
    }
    while (true) {
      safelyCall(endpoint) {
        message match {
          case RpcMessage(_sender, content, context) =>
            try {
              endpoint.receiveAndReply(context).applyOrElse[Any, Unit](content, { msg =>
                throw new SparkException(s"Unsupported message $message from ${_sender}")
              })
            } catch {
              case e: Throwable =>
                context.sendFailure(e)
                // Throw the exception -- this exception will be caught by the safelyCall function.
                // The endpoint's onError function will be called.
                throw e
            }

          case OneWayMessage(_sender, content) =>
            endpoint.receive.applyOrElse[Any, Unit](content, { msg =>
              throw new SparkException(s"Unsupported message $message from ${_sender}")
            })

          case OnStart =>
            endpoint.onStart()
            if (!endpoint.isInstanceOf[ThreadSafeRpcEndpoint]) {
              inbox.synchronized {
                if (!stopped) {
                  enableConcurrent = true
                }
              }
            }

          case OnStop =>
            val activeThreads = inbox.synchronized { inbox.numActiveThreads }
            assert(activeThreads == 1,
              s"There should be only a single active thread but found $activeThreads threads.")
            dispatcher.removeRpcEndpointRef(endpoint)
            endpoint.onStop()
            assert(isEmpty, "OnStop should be the last message")

          case RemoteProcessConnected(remoteAddress) =>
            endpoint.onConnected(remoteAddress)

          case RemoteProcessDisconnected(remoteAddress) =>
            endpoint.onDisconnected(remoteAddress)

          case RemoteProcessConnectionError(cause, remoteAddress) =>
            endpoint.onNetworkError(cause, remoteAddress)
        }
      }

      inbox.synchronized {
        // "enableConcurrent" will be set to false after `onStop` is called, so we should check it
        // every time.
        /*
        如果不允许多个线程同时处理message中的消息并且当前激活的线程数多于1个，那么需要当前线程退出并将numActiveThreads减1.
         */
        if (!enableConcurrent && numActiveThreads != 1) {
          // If we are not the only one worker, exit
          numActiveThreads -= 1
          return
        }
        message = messages.poll()
        // 已经没有消息要处理了
        if (message == null) {
          numActiveThreads -= 1
          return
        }
      }
    }
  }

  // Inbox未停止时向messages列表加入消息。
  def post(message: InboxMessage): Unit = inbox.synchronized {
    if (stopped) {
      // We already put "OnStop" into "messages", so we should drop further messages
      onDrop(message)
    } else {
      messages.add(message)
      false
    }
  }

  def stop(): Unit = inbox.synchronized {
    // The following codes should be in `synchronized` so that we can make sure "OnStop" is the last
    // message
    if (!stopped) {
      // We should disable concurrent here. Then when RpcEndpoint.onStop is called, it's the only
      // thread that is processing messages. So `RpcEndpoint.onStop` can release its resources
      // safely.
      enableConcurrent = false
      stopped = true
      /*
      向message中添加OnStop消息。
      为了能够处理OnStop消息，只有Inbox所属的EndpointData放入receivers中，其messages列表中的消息才会被处理。
      OnStop被处理时，通过调用Dispatcher的removeRpcEndpointRef方法，将RpcEndpoint与RpcEndpointRef的映射从缓存endpointRefs中移除。
       */
      messages.add(OnStop)
      // Note: The concurrent events in messages will be processed one by one.
    }
  }

  def isEmpty: Boolean = inbox.synchronized { messages.isEmpty }

  /**
   * Called when we are dropping a message. Test cases override this to test message dropping.
   * Exposed for testing.
   */
  protected def onDrop(message: InboxMessage): Unit = {
    logWarning(s"Drop $message because $endpointRef is stopped")
  }

  /**
   * Calls action closure, and calls the endpoint's onError function in the case of exceptions.
   */
  private def safelyCall(endpoint: RpcEndpoint)(action: => Unit): Unit = {
    try action catch {
      case NonFatal(e) =>
        try endpoint.onError(e) catch {
          case NonFatal(ee) =>
            if (stopped) {
              logDebug("Ignoring error", ee)
            } else {
              logError("Ignoring error", ee)
            }
        }
    }
  }

}
