package io.backchat.websocket
package tests

import org.specs2.Specification
import org.specs2.time.NoTimeConversions
import net.liftweb.json.DefaultFormats

object WebSocketClientSpec {

  def newServer(port: Int)(implicit wireFormat: WireFormat): WebSocketServer = WebSocketServer(ServerInfo("Test Echo Server", port = port)) {
    new WebSocketServerClient {
      def receive = {
        case TextMessage(text) ⇒ send(text)
      }
    }
  }
}

class WebSocketClientSpec extends Specification with NoTimeConversions { def is =
  "A WebSocketClient should" ^
    "" ! pending ^
  end

  implicit val wireFormat: WireFormat = new JsonProtocolWireFormat()(DefaultFormats)
}
