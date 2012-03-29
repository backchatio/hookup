package io.backchat.websocket
package examples

import net.liftweb.json._
import io.backchat.websocket.WebSocketServer.Exclude

object ChatServer {

  implicit val formats: Formats = DefaultFormats

  class ChatServerClient extends WebSocketServerClient {
    def receive = {
      case Disconnected(_) =>
        println("%s has left" format id)
        this >< ("%s has left" format id, Exclude(this))
      case Connected =>
        println("%s has joined" format id)
        broadcast("%s has joined" format id, Exclude(this))
      case TextMessage(text) =>
        println("broadcasting: " + text + " from " + id)
        this >< (text, Exclude(this))
    }
  }

  def main(args: Array[String]) {
    val server = WebSocketServer(ServerInfo("ChatServer", port = 8127))(new ChatServerClient)
    server.start
  }
}
