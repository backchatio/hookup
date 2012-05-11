---
layout: minimalist
---

# BackChat.io Hookup

A scala based client and server for websockets based on netty and akka futures.
It draws its inspiration from finagle, faye-websocket, zeromq, akka, ...

The aim of this project is to provide a websocket client in multiple languages an to be used in non-browser applications.
This client should be reliable by making a best effort not to lose any messages and gracefully recover from disconnections.

The server should serve regular websocket applications but can be configured for more reliability too.

## Features
To reach said goals this library implements:

### Protocol features:

These features are baked into the default `JsonProtocolWireFormat` or in the WebSocket spec.

#### Message Acking: 
You can decide if you want to ack a message on a per message basis.

{% highlight scala %}
client ! "the message".needsAck(within = 5 seconds)
{% endhighlight %}

#### PingPong
This is baked into the websocket protocol, the library ensures it really happens

### Client only features:

There are a number of extras baked into the client, of course they can be enabled and disabled based on config.

#### Reconnection

The client reconnects to the server on a backoff schedule indefinitely or for a maximum amount of times

#### Message buffering

During phases of disconnection it will buffer the messages to a file so that upon reconnection the messages will all be sent to the server.

## Usage

This library is available on maven central.

{% highlight scala %}
libraryDependencies += "io.backchat.hookup" %% "hookup" % "0.2.2"
{% endhighlight %}

#### Create a websocket server

{% highlight scala %}
import io.backchat.hookup._

(HookupServer(8125) {
  new HookupServerClient {
    def receive = {
      case TextMessage(text) =>
        println(text)
        send(text)
    }
  }
}).start
{% endhighlight %}

#### Create a websocket client

{% highlight scala %}
import io.backchat.hookup._

new DefaultHookupClient(HookupClientConfig(new URI("ws://localhost:8080/thesocket"))) {

  def receive = {
    case Disconnected(_) ⇒ 
      println("The websocket to " + uri.toASCIIString + " disconnected.")
    case TextMessage(message) ⇒ {
      println("RECV: " + message)
      send("ECHO: " + message)
    }
  }

  connect() onSuccess {
    case Success ⇒
      println("The websocket is connected to:"+this.uri.toASCIIString+".")
      system.scheduler.schedule(0 seconds, 1 second) {
        send("message " + messageCounter.incrementAndGet().toString)
      }
    case _ ⇒
  }
}
{% endhighlight %}

There are [code examples](https://github.com/backchatio/hookup/tree/master/src/main/scala/io/backchat/hookup /examples) that show all the events being raised and a chat server/client.

* Echo ([server](https://github.com/backchatio/hookup/blob/master/src/main/scala/io/backchat/hookup/examples/PrintingEchoServer.scala) | [client](https://github.com/backchatio/hookup/blob/master/src/main/scala/io/backchat/hookup/examples/PrintingEchoClient.scala))
* All Events ([server](https://github.com/backchatio/hookup/blob/master/src/main/scala/io/backchat/hookup/examples/PrintAllEventsServer.scala) | [client](https://github.com/backchatio/hookup/blob/master/src/main/scala/io/backchat/hookup/examples/PrintAllEventsClient.scala))
* Chat ([server](https://github.com/backchatio/hookup/blob/master/src/main/scala/io/backchat/hookup/examples/ChatServer.scala) | [client](https://github.com/backchatio/hookup/blob/master/src/main/scala/io/backchat/hookup/examples/ChatClient.scala))

## Patches
Patches are gladly accepted from their original author. Along with any patches, please state that the patch is your original work and that you license the work to the *backchat-websocket* project under the MIT License.

## License
MIT licensed. check the [LICENSE](https://github.com/backchatio/hookup/blob/master/LICENSE) file
