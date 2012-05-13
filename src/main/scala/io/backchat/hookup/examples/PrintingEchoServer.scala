package io.backchat.hookup
package examples

import net.liftweb.json._

object PrintingEchoServer {


  def main(args: Array[String]) {
    /// code_ref: default_server
    implicit val formats: Formats = DefaultFormats
    implicit val wireFormat: WireFormat = new JsonProtocolWireFormat

    val server = HookupServer(8125) {
      new HookupServerClient {
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
    server onStart {
      println("Server is started")
    }
    server.start
    /// end_code_ref
  }
}
