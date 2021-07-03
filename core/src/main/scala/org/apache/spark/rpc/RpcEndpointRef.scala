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

package org.apache.spark.rpc

import scala.concurrent.Future
import scala.reflect.ClassTag

import org.apache.spark.{SparkConf, SparkException}
import org.apache.spark.internal.Logging
import org.apache.spark.util.RpcUtils

/**
 * A reference for a remote [[RpcEndpoint]]. [[RpcEndpointRef]] is thread-safe.
 *
 * 要向一个远端的RpcEndpoint发起请求，你就必须持有这个RpcEndpoint的RpcEndpointRef。
 * 抽象类RpcEndpointRef定义了所有RpcEndpoint引用的属性与接口。
 */
private[spark] abstract class RpcEndpointRef(conf: SparkConf)
  extends Serializable with Logging {
  // RPC最大重新连接次数。可以使用spark.rpc.numRetries属性进行配置，默认为3次。
  private[this] val maxRetries = RpcUtils.numRetries(conf)
  // RPC每次重新连接需要等待的毫秒数。可以使用spark.rpc.retry.wait属性进行配置，默认值为3s
  private[this] val retryWaitMs = RpcUtils.retryWaitMs(conf)
  // RPC的ask操作的默认超时时间。可以使用spark.rpc.askTimeout或者spark.network.timeout属性进行配置，默认值为120s。
  // spark.rpc.askTimeout属性的优先级更高。
  private[this] val defaultAskTimeout = RpcUtils.askRpcTimeout(conf)

  /**
   * return the address for the [[RpcEndpointRef]]
   *
   * 返回当前RpcEndpointRef对应RpcEndpoint的RPC地址(RpcAddress)
   */
  def address: RpcAddress

  // 返回当前RpcEndpointRef对应RpcEndpoint的名称。
  def name: String

  /**
   * Sends a one-way asynchronous message. Fire-and-forget semantics.
   *
   * 发送单向异步的消息。所谓单向就是发送完后就会忘记此次发送，不会有任何状态要记录，也不会期望得到服务端的回复。
   */
  def send(message: Any): Unit

  /**
   * Send a message to the corresponding [[RpcEndpoint.receiveAndReply)]] and return a [[Future]] to
   * receive the reply within the specified timeout.
   *
   * This method only sends the message once and never retries.
   */
  def ask[T: ClassTag](message: Any, timeout: RpcTimeout): Future[T]

  /**
   * Send a message to the corresponding [[RpcEndpoint.receiveAndReply)]] and return a [[Future]] to
   * receive the reply within a default timeout.
   *
   * This method only sends the message once and never retries.
   *
   * 以默认的超时时间作为timeout参数。
   */
  def ask[T: ClassTag](message: Any): Future[T] = ask(message, defaultAskTimeout)

  /**
   * Send a message to the corresponding [[RpcEndpoint.receiveAndReply]] and get its result within a
   * default timeout, throw an exception if this fails.
   *
   * Note: this is a blocking action which may cost a lot of time,  so don't call it in a message
   * loop of [[RpcEndpoint]].

   * @param message the message to send
   * @tparam T type of the reply message
   * @return the reply message from the corresponding [[RpcEndpoint]]
   */
  def askSync[T: ClassTag](message: Any): T = askSync(message, defaultAskTimeout)

  /**
   * Send a message to the corresponding [[RpcEndpoint.receiveAndReply]] and get its result within a
   * specified timeout, throw an exception if this fails.
   *
   * Note: this is a blocking action which may cost a lot of time, so don't call it in a message
   * loop of [[RpcEndpoint]].
   *
   * @param message the message to send
   * @param timeout the timeout duration
   * @tparam T type of the reply message
   * @return the reply message from the corresponding [[RpcEndpoint]]
   */
  def askSync[T: ClassTag](message: Any, timeout: RpcTimeout): T = {
    val future = ask[T](message, timeout)
    timeout.awaitResult(future)
  }

}
