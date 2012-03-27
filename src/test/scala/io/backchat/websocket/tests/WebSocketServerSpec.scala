package io.backchat.websocket
package tests

import org.specs2.Specification
import java.nio.charset.Charset
import io.backchat.websocket.WebSocketServer.{WebSocketServerClient}
import org.specs2.specification.{Step, Fragments, After}
import akka.testkit._
import akka.util.duration._
import akka.dispatch.{Await, ExecutionContext, Promise}
import org.specs2.time.NoTimeConversions
import java.util.concurrent.{TimeUnit, CountDownLatch, TimeoutException, Executors}
import org.specs2.execute.Result
import java.net.{URI, ServerSocket, SocketAddress, InetSocketAddress}

class WebSocketServerSpec extends Specification with NoTimeConversions { def is = sequential ^
  "A WebSocketServer should" ^
    "fails connecting when none of the protocols match" ! webSocketServerContext("irc", "minutes").failsWithWrongSubProtocols ^ bt^
    "accept connections" ^ t ^
      "without subprotocols" ! webSocketServerContext().acceptsWithoutSubProtocols ^
      "with subprotocols" ! webSocketServerContext("irc", "minutes").acceptsWithSubProtocols ^ bt(2) ^
    "perform messaging and" ^ t^
      "receive a message from a client" ! webSocketServerContext().receivesClientMessages ^
      "send a message to a client" ! webSocketServerContext().canSendMessagesToTheClient ^ bt(2) ^
    "closing the connection" ^ t^
      "server can close a connection" ! webSocketServerContext().notifiesClientOfClose ^
      "client can close a connection" ! webSocketServerContext().removesClientOnClose ^ bt(2) ^
    "ping pong" ^ t^
      "initiated by the server if the client is idle for a while" ! pending ^
      "initiated by the client if the connection is idle for a while" ! pending ^ bt(2) ^
    "provide acking by" ^ t ^
      "expecting an ack" ! pending ^
      "acking a message" ! pending ^ bt(3) ^
  "When the server connection goes away, a WebSocket should " ^ t ^
    "reconnect according to a schedule" ! pending ^
    "buffer messages in a file while reconnecting" ! pending ^
    "buffer messages in memory while draining file buffer" ! pending ^
  end

  implicit val executionContext = ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())
  override def map(fs: => Fragments) = super.map(fs) ^ Step(executionContext.shutdown())


  case class webSocketServerContext(protocols: String*) extends After {
    import io.backchat.websocket.Connected
    val serverAddress = {
      val s = new ServerSocket(0); try { s.getLocalPort } finally { s.close() }
    }
    var messages = List.empty[String]
    var client = Promise[WebSocketServerClient]()
    val disconnectionLatch = new CountDownLatch(1)
    class WsClient extends WebSocketServerClient {
      def receive = {
        case Connected => client.complete(Right(this))
        case TextMessage(text) => {
          println("got text message")
          messages ::= text
        }
        case Disconnected(_) =>
          disconnectionLatch.countDown()
          println("server client disconnected")
        case e => println(e)
      }
    }
    val server = {
      val fn = if (protocols.isEmpty) WebSocketServer("127.0.0.1", serverAddress)_
      else WebSocketServer("127.0.0.1", serverAddress, SubProtocols(protocols.head, protocols.tail:_*))_
      fn(new WsClient)
    }

    server.start
    def after = {
      server.stop
    }

    def withClient[T <% Result](handler: WebSocket.Receive, protocols: String*)(thunk: WebSocket => T): T = {
      val protos = protocols
      val cl = new WebSocket {
        val uri = new URI("ws://127.0.0.1:"+serverAddress.toString+"/")

        override val protocols = protos

        def receive = handler
      }
      try {
        Await.ready(cl.connect.onSuccess({case x => println(x)}), 3 seconds)
        thunk(cl)
      } finally {
        cl.close
      }
    }

    def acceptsWithoutSubProtocols = this {
      withClient({ case _ => }) { c => (client.isCompleted must beTrue.eventually) and (c.isConnected must beTrue.eventually)  }
    }

    def acceptsWithSubProtocols = this {
      withClient({ case _ => }, protocols:_*) { c =>
        client.isCompleted must beTrue.eventually and (c.isConnected must beTrue.eventually)
      }
    }

    def failsWithWrongSubProtocols = pending // Until netty really does do the sub protocols
    //this {
//      withClient({ case _ => }, "xmpp") { _ => client.isCompleted must beTrue.eventually  }
//    }

    def canSendMessagesToTheClient = this {
      val toSend = TextMessage("this is some text you know")
      var rcvd: String = null
      withClient({
        case TextMessage(text) => rcvd = text
      }) { _ =>
        client.onSuccess({ case c => c ! toSend })
        rcvd must be_==(toSend.content).eventually
      }
    }

    def receivesClientMessages = this {
      val toSend = TextMessage("this is some text you know")
      var rcvd: String = null
      withClient({
        case TextMessage(text) => rcvd = text
      }) { c =>
        c send toSend
        messages.contains(toSend.content) must beTrue.eventually
      }
    }

    def notifiesClientOfClose = this {
      val toSend = TextMessage("this is some text you know")
      var rcvd: String = null
      withClient({
        case Disconnected(_) =>
      }) { c =>
        client.onSuccess({case c => c.close() })
        disconnectionLatch.await(2, TimeUnit.SECONDS) must beTrue and (c.isConnected must beFalse.eventually)
      }
    }

    def removesClientOnClose = this {
      val toSend = TextMessage("this is some text you know")
      var rcvd: String = null
      withClient({
        case Disconnected(_) =>
      }) { c =>
        c.close()
        disconnectionLatch.await(2, TimeUnit.SECONDS) must beTrue and (c.isConnected must beFalse.eventually)
      }

    }
  }
}
