package io.backchat.hookup
package examples

import net.liftweb.json._

object ChatServer {

  implicit val wireFormat: WireFormat = new JsonProtocolWireFormat()(DefaultFormats)

  def main(args: Array[String]) {
    val server = HookupServer(ServerInfo("ChatServer", port = 8127)){
      new HookupServerClient {
        def receive = {
          case Disconnected(_) ⇒
            println("%s has left" format id)
            this >< "%s has left".format(id)
          case Connected ⇒
            println("%s has joined" format id)
            broadcast("%s has joined" format id)
          case TextMessage(text) ⇒
            println("broadcasting: " + text + " from " + id)
            this >< text
        }
      }
    }
    server.start
  }
}
