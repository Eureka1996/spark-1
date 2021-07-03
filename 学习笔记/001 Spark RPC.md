
## Spark RPC


Spark2.X虽然剥离了Akka，但是还是沿袭了Actor模式中的一些概念。

Spark RPC中有如下映射关系
```
RpcEndpoint => Actor
RpcEndpointRef => ActorRef
RpcEnv => ActorSystem
```


### RpcEndpoint和RpcCallContext

RpcEndpoint是对Spark的RPC通信实体的统一抽象，所有运行于RPC框架之上的实体都应该继承RpcEndpoint.



RpcEndpoint是一个可以响应请求的服务，和Akka中的Actor类似。


## RPC客户端

![](picture/Spark%20RPC客户端发送请求示意图.png)


序号①：表示通过调用NettyRpcEndpointRef的send和ask方法向本地节点的RpcEndpoint发送消息。
由于是在同一节点，所以直接调用Dispatcher的postLocalMessage或postOneWayMessage方法将
消息放入EndpointData内部Inbox的messages列表中。MessageLoop线程最后处理消息，并将消息
发给对应的RpcEndpoint处理。

序号②：表示通过调用NettyRpcEndpointRef的send和ask方法向远端节点的RpcEndpoint发送消息。
这种情况下，消息将首先被封装为OutboxMessage，然后放入到远端RpcEndpoint的地址所对应的
Outbox的messages列表中。

序号③：表示每个Outbox的drainOutbox方法通过循环，不断从messages列表中取得OutboxMessage。

序号④：表示每个Outbox的drainOutbox方法使用Outbox内部的TransportClient向远端的NettyRpcEnv
发送序号③中取得的OutboxMessage。

序号⑤：表示序号④发出的请求在与远端NettyRpcEnv的TransportServer建立了连接后，请求消息
首先经过Netty管道的处理，然后经由NettyRpcHandler处理，最后NettyRpcHandler的receive方法
会调用Dispatcher的postRemoteMessage或postOneWayMessage方法，将消息放入EndpointData内部
Inbox的messages列表中。MessageLoop线程最后处理消息，并将消息发给对应的RpcEndpoint处理。




 
 ⑥ ⑦ ⑧ ⑨ ⑩
