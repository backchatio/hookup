package io.backchat.websocket
package examples

import net.liftweb.json._

object PrintingEchoServer {

  implicit val wireFormat: WireFormat = new LiftJsonWireFormat()(DefaultFormats)

  def main(args: Array[String]) {
    val server = WebSocketServer(8125) {
      new WebSocketServerClient {
        def receive = {
          case TextMessage(text) ⇒
            println(text)
            send(text)
        }
      }
    }
    server onStop {
      println("Server is stopped")
    }
    server.start
  }
}
