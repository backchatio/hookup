# Backchat WebSocket

A scala based client for websockets based on netty and akka futures.
It draws its inspiration from finagle, faye-websocket, zeromq, akka, ...

The aim of this project is to provide a websocket client aimed to be used in non-browser applications.
This client should be reliable by making a best effort to not lose any messages and gracefully recover from disconnections.

The server should serve regular websocket applications but can be configured for more reliability too.

## Features
To reach said goals this library implements:

* Message Acking: you can decide if you want to ack a message on a per message basis
```scala
client ! "the message".needsAck(within = 5 seconds)
```
* PingPong: this is baked into the websocket protocol, the library ensures it really happens

In addition to the shared items the client optionally does:

* reconnect to the server on a backoff schedule indefinitely or for a max amount of times
* during phases of disconnection it will buffer the messages to a file so that upon reconnection the messages will all be sent to the server.

## Usage

#### Create a websocket server

```scala
val server = WebSocketServer(8125) {
  new WebSocketServerClient {

    def receive = {
      case TextMessage(text) =>
        println(text)
        send(text)
    }

  }
}

sys.addShutdownHook(server.stop)
server.start
```

#### Create a websocket client

```scala
new WebSocket with BufferedWebSocket {

  val uri = URI.create("ws://localhost:8125/")

  def receive = {
    case TextMessage(text) =>
      println("RECV: " + text)
  }

  connect() onSuccess {
    case _ =>
      println("connected to: %s" format uri.toASCIIString)
      system.scheduler.schedule(0 seconds, 1 second) {
        send("hello")
      }
  }
}
```

check out the [examples](https://github.com/casualjim/scala-websocket/tree/master/src/main/scala/io/backchat/websocket/examples) in the source tree to see the complete code.

## Patches
Patches are gladly accepted from their original author. Along with any patches, please state that the patch is your original work and that you license the work to the *rl* project under the MIT License.

## License
MIT licensed. check the [LICENSE](https://github.com/casualjim/scala-websocket/blob/master/LICENSE) file

