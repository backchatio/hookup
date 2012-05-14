package io.backchat.hookup
package tests

import org.specs2.Specification
import org.specs2.time.NoTimeConversions
import net.liftweb.json.DefaultFormats
import org.specs2.execute.Result
import akka.dispatch.Await
import java.net.{ServerSocket, URI}
import java.util.concurrent.TimeoutException
import akka.testkit._
import akka.actor.ActorSystem
import net.liftweb.json.JsonAST.{JField, JString, JObject}
import akka.util.duration._
import org.specs2.specification.{Around, Step, Fragments}

object HookupClientSpecification {

  def newServer(port: Int, defaultProtocol: String = "jsonProtocol"): HookupServer =
    HookupServer(ServerInfo("Test Echo Server", defaultProtocol = defaultProtocol, listenOn = "127.0.0.1", port = port)) {
      new HookupServerClient {
        def receive = {
          case TextMessage(text) ⇒ send(text)
          case JsonMessage(json) => send(json)
        }
      }
    }
}

trait HookupClientSpecification  {


  val serverAddress = {
    val s = new ServerSocket(0);
    try { s.getLocalPort } finally { s.close() }
  }
  def server: Server

  type Handler = PartialFunction[(HookupClient, InboundMessage), Any]

  val uri = new URI("ws://127.0.0.1:"+serverAddress.toString+"/")
  val defaultClientConfig = HookupClientConfig(uri, defaultProtocol = new JsonProtocolWireFormat()(DefaultFormats))
  def withWebSocket[T <% Result](handler: Handler, config: HookupClientConfig = defaultClientConfig)(t: HookupClient => T) = {
    val client = new HookupClient {

      val settings = config
      def receive = {
        case m  => handler.lift((this, m))
      }
    }
    Await.ready(client.connect(), 5 seconds)
    try { t(client) } finally { try { Await.ready(client.disconnect(), 2 seconds) } catch { case e => e.printStackTrace() }}
  }

}

class HookupClientSpec extends Specification with NoTimeConversions { def is =
  "A WebSocketClient should" ^
    "when configured with jsonProtocol" ^
      "connect to a server" ! specify("jsonProtocol").connectsToServer ^
      "exchange json messages with the server" ! specify("jsonProtocol").exchangesJsonMessages ^ bt ^
    "when configured with simpleJsonProtocol" ^
      "connect to a server" ! specify("simpleJson").connectsToServerSimpleJson ^
      "exchange json messages with the server" ! specify("simpleJson").exchangesJsonMessagesSimpleJson ^
  end

  implicit val system: ActorSystem = ActorSystem("HookupClientSpec")

  def specify(proto: String) = new ClientSpecContext(proto)

  class ClientSpecContext(defaultProtocol: String) extends HookupClientSpecification with Around {

    val server = HookupClientSpecification.newServer(serverAddress, defaultProtocol)

    def around[T <% Result](t: => T) = {
      server.start
      val r = t
      server.stop
      r
    }

    def connectsToServer = this {
      val latch = TestLatch()
      withWebSocket({
        case (_, Connected) => latch.open()
      }) { _ => Await.result(latch, 5 seconds) must not(throwA[TimeoutException]) }
    }

    def exchangesJsonMessages = this {
      val latch = TestLatch()
      withWebSocket({
        case (client, Connected) => client send JObject(JField("hello", JString("world")) :: Nil)
        case (client, JsonMessage(JObject(JField("hello", JString("world")) :: Nil))) => latch.open
      }) { _ => Await.result(latch, 5 seconds) must not(throwA[TimeoutException]) }
    }

    def connectsToServerSimpleJson = this {
      val latch = TestLatch()
      withWebSocket({
        case (_, Connected) => latch.open()
      }, HookupClientConfig(uri)) { _ => Await.result(latch, 5 seconds) must not(throwA[TimeoutException]) }
    }

    def exchangesJsonMessagesSimpleJson = this {
      val latch = TestLatch()
      withWebSocket({
        case (client, Connected) => client send JObject(JField("hello", JString("world")) :: Nil)
        case (client, JsonMessage(JObject(JField("hello", JString("world")) :: Nil))) => latch.open
      }, HookupClientConfig(uri)) { _ => Await.result(latch, 5 seconds) must not(throwA[TimeoutException]) }
    }
  }
}
