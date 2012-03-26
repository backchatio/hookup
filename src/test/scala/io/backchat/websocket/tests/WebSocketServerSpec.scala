package io.backchat.websocket
package tests

import org.specs2.Specification
import java.nio.charset.Charset
import io.backchat.websocket.WebSocketServer.{TextMessage, Connect, Disconnected, WebSocketServerClient}
import org.specs2.specification.{Step, Fragments, After}
import akka.testkit._
import akka.util.duration._
import akka.dispatch.{Await, ExecutionContext, Promise}
import org.specs2.time.NoTimeConversions
import java.util.concurrent.{TimeUnit, CountDownLatch, TimeoutException, Executors}
import java.net.{ServerSocket, SocketAddress, InetSocketAddress}
import org.specs2.execute.Result
import io.backchat.websocket.WebSocketClient.Messages.{Connecting, Connected}

class WebSocketServerSpec extends Specification with NoTimeConversions { def is = sequential ^
  "A WebSocketServer should" ^
    "accept connections" ^
      "without subprotocols" ! webSocketServerContext().acceptsWithoutSubProtocols ^
      "with subprotocols" ! webSocketServerContext().acceptsWithSubProtocols ^ bt ^
    "perform messaging and" ^
      "receive a message from a client" ! webSocketServerContext().receivesClientMessages ^
      "send a message to a client" ! webSocketServerContext().canSendMessagesToTheClient ^
  end

  implicit val executionContext = ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())
  override def map(fs: => Fragments) = super.map(fs) ^ Step(executionContext.shutdown())


  case class webSocketServerContext() extends After {

    val serverAddress = {
      val s = new ServerSocket(0); try { s.getLocalPort } finally { s.close() }
    }
    var messages = List.empty[String]
    var client = Promise[WebSocketServerClient]()
    val server = WebSocketServer("127.0.0.1", serverAddress) {
      new WebSocketServerClient {
        def receive = {
          case Connect => client.complete(Right(this))
          case TextMessage(text) => {
            messages ::= text
          }
          case Disconnected(_) => println("server client disconnected")
          case e => println(e)
        }
      }
    }

    server.start
    def after = {
      server.stop
    }

    def withClient[T <% Result](handler: WebSocketClient.Handler)(thunk: WebSocketClient => T): T = {
      val cl = WebSocketClient("127.0.0.1", serverAddress)(handler)
      try {
        cl.connect
        thunk(cl)
      } finally {
        cl.disconnect
      }
    }

    def acceptsWithoutSubProtocols = this {
      val latch = new CountDownLatch(1)
      withClient({
        case Connected(_) => latch.countDown()
      }) { _ => latch.await(2, TimeUnit.SECONDS) must beTrue and { client.isCompleted must beTrue.eventually } }
    }
    def acceptsWithSubProtocols = pending // TODO: fix this when the client knows about subprotocols

    def canSendMessagesToTheClient = this {
      val latch = new CountDownLatch(1)
      val toSend = "this is some text you know"
      var rcvd: String = null
      withClient({
        case Connected(_) => latch.countDown()
        case WebSocketClient.Messages.TextMessage(_, text) => rcvd = text
      }) { _ =>
        latch.await(2, TimeUnit.SECONDS) must beTrue and {
          client.onSuccess({ case c => c ! toSend })
          rcvd must be_==(toSend).eventually
        }
      }
    }

    def receivesClientMessages = this {
      val latch = new CountDownLatch(1)
      val toSend = "this is some text you know"
      var rcvd: String = null
      withClient({
        case Connected(_) => latch.countDown()
        case WebSocketClient.Messages.TextMessage(_, text) => rcvd = text
      }) { c =>
        latch.await(2, TimeUnit.SECONDS) must beTrue and {
          c send toSend
          messages.contains(toSend) must beTrue.eventually
        }
      }
    }
    def notifiesClientOfClose = pending
    def removesClientOnClose = pending
  }
}
