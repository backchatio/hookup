package io.backchat.websocket
package examples

import net.liftweb.json._

object PrintAllEventsServer {

  implicit val formats: Formats = DefaultFormats

  def main(args: Array[String]) {
    val server = WebSocketServer(8126) {
      new WebSocketServerClient {
        def receive = {
          case Connected =>
            println("client connected")
          case Disconnected(_) =>
            println("client disconnected")
          case m @ Error(exOpt) =>
            System.err.println("Received an error: " + m)
            exOpt foreach { _.printStackTrace(System.err) }
          case m: TextMessage =>
            println(m)
            send(m)
          case m: JsonMessage =>
            println("JsonMessage(" + pretty(render(m.content)) + ")")
            send(m)
        }
      }
    }

    server onStart {
      println("Server is starting")
    }
    server onStop {
      println("Server is stopping")
    }
    server.start
  }

}
