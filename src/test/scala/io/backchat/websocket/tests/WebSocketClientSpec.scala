package io.backchat.websocket
package tests

import org.specs2.Specification
import org.specs2.time.NoTimeConversions
import net.liftweb.json.DefaultFormats
import org.specs2.execute.Result
import akka.dispatch.Await
import java.net.{ServerSocket, URI}
import org.specs2.specification.{Step, Fragments}
import java.util.concurrent.TimeoutException
import akka.testkit._
import akka.actor.ActorSystem
import net.liftweb.json.JsonAST.{JField, JString, JObject}
import akka.util.duration._

object WebSocketClientSpecification {

  def newServer(port: Int)(implicit wireFormat: WireFormat): WebSocketServer =
    WebSocketServer(ServerInfo("Test Echo Server", listenOn = "127.0.0.1", port = port)) {
      new WebSocketServerClient {
        def receive = {
          case TextMessage(text) ⇒ send(text)
          case JsonMessage(json) => send(json)
        }
      }
    }
}

trait WebSocketClientSpecification extends Specification with NoTimeConversions {

  implicit val wireFormat: WireFormat = new JsonProtocolWireFormat()(DefaultFormats)
  val serverAddress = {
    val s = new ServerSocket(0);
    try { s.getLocalPort } finally { s.close() }
  }
  def server: Server


  override def map(fs: => Fragments) =
    Step(server.start) ^ super.map(fs) ^ Step(server.stop)

  type Handler = PartialFunction[(WebSocket, WebSocketInMessage), Any]

  def withWebSocket[T <% Result](handler: Handler)(t: WebSocket => T) = {
    val client = new WebSocket {
      val uri = new URI("ws://127.0.0.1:"+serverAddress.toString+"/")
      override implicit val wireFormat: WireFormat = new JsonProtocolWireFormat()(jsonFormats)
      val settings = WebSocketContext(uri)
      def receive = {
        case m  => handler.lift((this, m))
      }
    }
    Await.ready(client.connect(), 5 seconds)
    try { t(client) } finally { try { Await.ready(client.disconnect(), 2 seconds) } catch { case e => e.printStackTrace() }}
  }

}

class WebSocketClientSpec extends  WebSocketClientSpecification { def is =
  "A WebSocketClient should" ^
    "connects to server" ! connectsToServer ^
    "exchange json messages with the server" ! pending ^
  end

  implicit val system: ActorSystem = ActorSystem("WebSocketClientSpec")
  val server = WebSocketClientSpecification.newServer(serverAddress)


  def connectsToServer = {
    val latch = TestLatch()
    withWebSocket({
      case (_, Connected) => latch.open()
    }) { _ => Await.result(latch, 5 seconds) must not(throwA[TimeoutException]) }
  }

  def exchangesJsonMessages = {
    val latch = TestLatch()
    withWebSocket({
      case (client, Connected) => client send JObject(JField("hello", JString("world")) :: Nil)
    }) { _ => Await.result(latch, 5 seconds) must not(throwA[TimeoutException]) }
  }
}
