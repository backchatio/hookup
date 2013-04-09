package io.backchat.hookup
package examples

import org.specs2.Specification
import org.specs2.time.NoTimeConversions
import scala.concurrent.duration._
import akka.testkit._
import net.liftweb.json.{Formats, DefaultFormats}
import akka.util.Timeout
import java.net.{InetSocketAddress, SocketAddress, ServerSocket, Socket}
import java.io.{BufferedReader, PrintWriter, InputStreamReader}
import java.util.concurrent.{TimeUnit, CountDownLatch, TimeoutException}
import scala.concurrent.{Future, Await}


class NoopWireformat(val name: String, val supportsAck: Boolean = false) extends WireFormat {

  def parseInMessage(message: String) = null

  def parseOutMessage(message: String) = null

  def render(message: OutboundMessage) = null
}
class ServerConfigurationsExample extends Specification with NoTimeConversions { def is =
  "A Server with a ping configuration" ! serverWithPing ^
  "A Server with a content compression configuration" ! serverWithContentCompression ^
  "A Server with a max frame configuration" ! serverWithMaxFrame ^
  "A Server with a ssl configuration" ! serverWithSslSupport ^
  "A Server with a subprotocols configuration" ! serverWithSubprotocols ^
  "A Server with a flash policy configuration" ! serverWithFlashPolicy ^ end

  def serverWithPing = {
    /// code_ref: server_with_ping
    implicit val jsonFormats: Formats = DefaultFormats
    implicit val wireFormat: WireFormat = new JsonProtocolWireFormat

    HookupServer(Ping(Timeout(2 minutes))) {
      new HookupServerClient {
        def receive = { case _ =>}
      }
    }
    /// end_code_ref
    success
  }

  def serverWithContentCompression = {
    /// code_ref: server_with_compression
    HookupServer(ContentCompression(2)) {
      new HookupServerClient {
        def receive = { case _ =>}
      }
    }
    /// end_code_ref
    success
  }

  def serverWithMaxFrame = {
    /// code_ref: server_with_max_frame
    HookupServer(MaxFrameSize(512*1024)) {
      new HookupServerClient {
        def receive = { case _ =>}
      }
    }
    /// end_code_ref
    success
  }

  def serverWithSslSupport = {
    try {
      /// code_ref: server_with_ssl
      val sslSupport =
        SslSupport(
          keystorePath = "./ssl/keystore.jks",
          keystorePassword = "changeme",
          algorithm = "SunX509")

      HookupServer(sslSupport) {
        new HookupServerClient {
          def receive = { case _ =>}
        }
      }
      /// end_code_ref
    } catch {
      case _ =>
    }
    success
  }



  def serverWithSubprotocols = {
    /// code_ref: server_with_subprotocols
    // these wire formats aren't actually implemented it's just to show the idea
    HookupServer(SubProtocols(new NoopWireformat("irc"), new NoopWireformat("xmpp"))) {
      new HookupServerClient {
        def receive = { case _ =>}
      }
    }
    /// end_code_ref
    success
  }

  def serverWithFlashPolicy = {
    val latch = new CountDownLatch(1)
    val port =  {
      val s = new ServerSocket(0);
      try { s.getLocalPort } finally { s.close() }
    }
    import HookupClient.executionContext
    /// code_ref: server_with_flash_policy
    val server = HookupServer(port, FlashPolicy("*.example.com", Seq(80, 443, 8080, 8843, port))) {
      new HookupServerClient {
        def receive = { case _ =>}
      }
    }
    /// end_code_ref
    server onStart {
      latch.countDown
    }
    server.start
    latch.await(2, TimeUnit.SECONDS) must beTrue and {
      val socket = new Socket
      socket.connect(new InetSocketAddress("localhost", port), 2000)
      val out = new PrintWriter(socket.getOutputStream)

      val in = new BufferedReader(new InputStreamReader(socket.getInputStream))
      out.println("<policy-file-request/>%c" format 0)
      out.flush()
      val recv = Future {
        val sb = new Array[Char](159)
        var line = in.read(sb)
        val resp = new String(sb)
        resp
      }

      val res = Await.result(recv, 3 seconds)
      in.close()
      out.close()
      socket.close()
      server.stop
      res must contain("*.example.com") and (res must contain(port.toString))
    }
  }
}
