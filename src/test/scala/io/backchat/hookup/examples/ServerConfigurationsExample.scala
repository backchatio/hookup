package io.backchat.hookup
package examples

import org.specs2.Specification
import org.specs2.time.NoTimeConversions
import akka.util.duration._
import akka.testkit._
import net.liftweb.json.{Formats, DefaultFormats}
import akka.util.Timeout

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
    implicit val jsonFormats: Formats = DefaultFormats
    implicit val wireFormat: WireFormat = new JsonProtocolWireFormat

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
    implicit val jsonFormats: Formats = DefaultFormats
    implicit val wireFormat: WireFormat = new JsonProtocolWireFormat

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
      implicit val jsonFormats: Formats = DefaultFormats
      implicit val wireFormat: WireFormat = new JsonProtocolWireFormat

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
    implicit val jsonFormats: Formats = DefaultFormats
    implicit val wireFormat: WireFormat = new JsonProtocolWireFormat

    HookupServer(SubProtocols("irc", "xmpp")) {
      new HookupServerClient {
        def receive = { case _ =>}
      }
    }
    /// end_code_ref
    success
  }

  def serverWithFlashPolicy = {
    /// code_ref: server_with_flash_policy
    implicit val jsonFormats: Formats = DefaultFormats
    implicit val wireFormat: WireFormat = new JsonProtocolWireFormat

    HookupServer(FlashPolicy("*.example.com", Seq(80, 443, 8080, 8843))) {
      new HookupServerClient {
        def receive = { case _ =>}
      }
    }
    /// end_code_ref
    success
  }
}
