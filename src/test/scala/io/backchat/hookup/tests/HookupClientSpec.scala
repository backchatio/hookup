package io.backchat.hookup
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

object HookupClientSpecification {

  def newServer(port: Int): HookupServer =
    HookupServer(ServerInfo("Test Echo Server", defaultProtocol = "jsonProtocol", listenOn = "127.0.0.1", port = port)) {
      new HookupServerClient {
        def receive = {
          case TextMessage(text) ⇒ send(text)
          case JsonMessage(json) => send(json)
        }
      }
    }
}

trait HookupClientSpecification extends Specification with NoTimeConversions {


  val serverAddress = {
    val s = new ServerSocket(0);
    try { s.getLocalPort } finally { s.close() }
  }
  def server: Server


  override def map(fs: => Fragments) =
    Step(server.start) ^ super.map(fs) ^ Step(server.stop)

  type Handler = PartialFunction[(HookupClient, InboundMessage), Any]

  def withWebSocket[T <% Result](handler: Handler)(t: HookupClient => T) = {
    val client = new HookupClient {
      val uri = new URI("ws://127.0.0.1:"+serverAddress.toString+"/")
      val settings = HookupClientConfig(uri, defaultProtocol = new JsonProtocolWireFormat()(DefaultFormats))
      def receive = {
        case m  => handler.lift((this, m))
      }
    }
    Await.ready(client.connect(), 5 seconds)
    try { t(client) } finally { try { Await.ready(client.disconnect(), 2 seconds) } catch { case e => e.printStackTrace() }}
  }

}

class HookupClientSpec extends  HookupClientSpecification { def is =
  "A WebSocketClient should" ^
    "connects to server" ! connectsToServer ^
    "exchange json messages with the server" ! pending ^
  end

  implicit val system: ActorSystem = ActorSystem("HookupClientSpec")
  val server = HookupClientSpecification.newServer(serverAddress)


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
