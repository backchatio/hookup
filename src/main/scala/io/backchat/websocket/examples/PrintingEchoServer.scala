package io.backchat.websocket
package examples

import io.backchat.websocket.WebSocketServer
import io.backchat.websocket.WebSocketServer.WebSocketServerClient
import net.liftweb.json.{Formats, DefaultFormats}

object PrintingEchoServer {

  implicit val formats: Formats = DefaultFormats

  def main(args: Array[String]) {
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
  }
}
