package io.backchat.hookup
package tests

import org.specs2.Specification
import org.specs2.specification.{Step, Fragments, After}
import akka.util.duration._
import akka.dispatch.{Await, ExecutionContext, Promise}
import org.specs2.time.NoTimeConversions
import org.specs2.execute.Result
import java.net.{URI, ServerSocket}
import akka.util.Timeout
import net.liftweb.json._
import JsonDSL._
import java.io.File
import akka.testkit._
import java.util.concurrent._
import examples.NoopWireformat

class HookupServerSpec extends Specification with NoTimeConversions { def is = sequential ^
  "A HookupServer should" ^
    "fails connecting when none of the protocols match" ! hookupServerContext(protos:_*).failsWithWrongSubProtocols ^ bt^
    "accept connections" ^ t ^
      "without subprotocols" ! hookupServerContext().acceptsWithoutSubProtocols ^
      "with subprotocols" ! hookupServerContext(protos:_*).acceptsWithSubProtocols ^ bt(2) ^
    "perform messaging and" ^ t^
      "receive a message from a client" ! hookupServerContext().receivesClientMessages ^
      "detect when a json message is received" ! hookupServerContext().receivesJsonClientMessages ^
      "detect when a json message is sent" ! hookupServerContext().sendsJsonClientMessages ^
      "send a message to a client" ! hookupServerContext().canSendMessagesToTheClient ^ bt(2) ^
    "close the connection" ^ t^
      "initiated by the server" ! hookupServerContext().notifiesClientOfClose ^
      "initiated by the client" ! hookupServerContext().removesClientOnClose ^ bt(2) ^
    "provide acking by" ^ t ^
      "expecting an ack on the server" ! hookupServerContext().serverExpectsAnAck ^
      "expecting an ack on the client" ! hookupServerContext().clientExpectsAnAck ^ bt(3) ^
  end

  def protos = Seq(new NoopWireformat("irc"), new NoopWireformat("minutes"))

  implicit val executionContext = ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())

  override def map(fs: => Fragments) = super.map(fs) ^ Step(executionContext.shutdown())


  case class hookupServerContext(protocols: WireFormat*) extends After {
    import collection.JavaConverters._
    import io.backchat.hookup.Connected
    val serverAddress = {
      val s = new ServerSocket(0);
      try { s.getLocalPort } finally { s.close() }
    }
    var messages = new CopyOnWriteArrayList[String]().asScala
    var jsonMessages = new CopyOnWriteArrayList[JValue]().asScala
    var client = Promise[HookupServerClient]()
    val disconnectionLatch = new CountDownLatch(1)
    val ackRequest = new CountDownLatch(2)

    class WsClient extends HookupServerClient {
      def receive = {
        case Connected => client.complete(Right(this))
        case TextMessage(text) => {
          messages :+= text
        }
        case JsonMessage(json) => {
          jsonMessages :+= json
        }
        case Disconnected(_) =>
          disconnectionLatch.countDown()
        case m: AckRequest => ackRequest.countDown()
        case m: Ack => ackRequest.countDown()
        case e =>
          println("unhandled server")
          println(e)
      }
    }
    val server = {
      val info = ServerInfo(
        listenOn = "127.0.0.1", defaultProtocol = "jsonProtocol", port = serverAddress,
        capabilities = if (protocols.isEmpty)
          Seq(Ping(Timeout(2 seconds)), RaiseAckEvents) else
          Seq(SubProtocols(protocols.head.name -> protocols.head, protocols.tail.map(p => p.name -> p):_*)))
      HookupServer(info)(new WsClient)
    }

    server.start
    def after = {
      server.stop
    }

    def withClient[T <% Result](handler: HookupClient.Receive, protocols: WireFormat*)(thunk: HookupClient => T): T = {
      val protos = protocols
      implicit val wireFormat = new JsonProtocolWireFormat()(DefaultFormats)
      val cl = new HookupClient {
        val uri = new URI("ws://127.0.0.1:"+serverAddress.toString+"/")
        val settings: HookupClientConfig = HookupClientConfig(
          uri = uri,
          throttle = IndefiniteThrottle(1 second, 1 second),
          buffer = Some(new FileBuffer(new File("./work/buffer-test.log"))),
          defaultProtocol = wireFormat,
          protocols = Map(protocols.map(w => w.name -> w):_*))
        override private[hookup] def raiseEvents = true
        def receive = handler
      }
      try {
        Await.ready(cl.connect("jsonProtcol"), 3 seconds)
        thunk(cl)
      } finally {
        cl.disconnect
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

    def failsWithWrongSubProtocols = this {
//      withClient({ case _ => }, new NoopWireformat("xmpp")) { c =>
//        client.isCompleted must beTrue.eventually //and (c.isConnected must beFalse.eventually)
//      }
      skipped
    }

    def canSendMessagesToTheClient = this {
      val toSend = TextMessage("this is some text you know")
      var rcvd: String = null
      val l = new CountDownLatch(1)
      withClient({
        case Connected => l.countDown()
        case TextMessage(text) => rcvd = text
      }) { _ =>
        l.await(3, TimeUnit.SECONDS) must beTrue and {
          client.onSuccess({ case c => c ! toSend })
          rcvd must be_==(toSend.content).eventually
        }
      }
    }

    def receivesClientMessages = this {
      val toSend = TextMessage("this is some text you know")
      var rcvd: String = null
      val l = new CountDownLatch(1)
      withClient({
        case Connected => l.countDown()
        case TextMessage(text) => rcvd = text
      }) { c =>
        l.await(3, TimeUnit.SECONDS) must beTrue and {
          c send toSend
          messages.contains(toSend.content) must beTrue.eventually
        }
      }
    }

    def receivesJsonClientMessages = this {
      val txt = "this is some text you know"
      val toSend: JValue = ("data" -> txt)
      var rcvd: JValue = null
      val l = new CountDownLatch(1)
      withClient({
        case Connected => l.countDown()
        case JsonMessage(text) => rcvd = text
      }) { c =>
        l.await(3, TimeUnit.SECONDS) must beTrue and {
          c send toSend
          jsonMessages.contains(toSend) must beTrue.eventually
        }
      }
    }

    def sendsJsonClientMessages = this {
      val txt = "this is some text you know"
      val toSend: JValue = ("data" -> txt)
      var rcvd: JValue = null
      val l = new CountDownLatch(1)
      withClient({
        case Connected => l.countDown
        case JsonMessage(text) => rcvd = text
      }) { c =>
        l.await(3, TimeUnit.SECONDS) must beTrue and {
          client.onSuccess({ case c => c ! toSend })
          rcvd must be_==(toSend).eventually
        }
      }
    }

    def notifiesClientOfClose = this {
      val toSend = TextMessage("this is some text you know")
      var rcvd: String = null
      val latch = new CountDownLatch(1)
      withClient({
        case Connected => latch.countDown
        case Disconnected(_) =>
      }) { c =>
        latch.await(3, TimeUnit.SECONDS) must beTrue and {
          client.onSuccess({case c => c.disconnect() })
          disconnectionLatch.await(2, TimeUnit.SECONDS) must beTrue and (c.isConnected must beFalse.eventually)
        }
      }
    }

    def removesClientOnClose = this {
      val toSend = TextMessage("this is some text you know")
      var rcvd: String = null
      val latch = new CountDownLatch(1)
      withClient({
        case Connected => latch.countDown
        case Disconnected(_) =>
      }) { c =>
        latch.await(3, TimeUnit.SECONDS) must beTrue and {
          c.disconnect()
          disconnectionLatch.await(2, TimeUnit.SECONDS) must beTrue and (c.isConnected must beFalse.eventually)
        }
      }

    }

    def serverExpectsAnAck = this {
      val toSend: JValue = ("data" -> "this is some text you know")
      withClient({
        case _ =>
       }) { _ =>
        client.onSuccess({ case c => c ! toSend.needsAck(within = 5 seconds) })
        ackRequest.await(3, TimeUnit.SECONDS) must beTrue
      }
    }

    def clientExpectsAnAck = this {
      val txt = "this is some text you know"
      val toSend: JValue = ("data" -> txt)
      val connected = new CountDownLatch(1)
      val latch = new CountDownLatch(2)
      withClient({
        case Connected => connected.countDown
        case m: AckRequest => latch.countDown
        case m: Ack => latch.countDown
      }) { c =>
        connected.await(3, TimeUnit.SECONDS) must beTrue and {
          c send toSend.needsAck(within = 5 seconds)
          latch.await(3, TimeUnit.SECONDS) must beTrue
        }
      }
    }

  }
}
