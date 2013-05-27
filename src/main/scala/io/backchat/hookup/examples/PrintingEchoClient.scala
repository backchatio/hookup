package io.backchat.hookup
package examples

import java.net.URI
import net.liftweb.json.{ DefaultFormats, Formats }
import akka.actor.ActorSystem
import scala.concurrent.duration._
import java.util.concurrent.atomic.AtomicInteger
import java.io.File

object PrintingEchoClient {

  val messageCounter = new AtomicInteger(0)

  def main(args: Array[String]) {

    val system = ActorSystem("PrintingEchoClient")

    /// code_ref: default_client
    new HookupClient {
      val uri = URI.create("ws://localhost:8125/")

      val settings: HookupClientConfig = HookupClientConfig(uri = uri)

      def receive = {
        case TextMessage(text) ⇒
          println("RECV: " + text)
      }

      connect() onSuccess {
        case _ ⇒
          println("connected to: %s" format uri.toASCIIString)
          system.scheduler.schedule(0 seconds, 1 second) {
            send("message " + messageCounter.incrementAndGet().toString)
          }
      }
    }
    /// end_code_ref
  }
}
