package io.backchat.websocket
package examples

import java.net.URI
import net.liftweb.json.{ DefaultFormats, Formats }
import akka.actor.ActorSystem
import akka.util.duration._
import java.util.concurrent.atomic.AtomicInteger

object PrintingEchoClient {

  implicit val formats: Formats = DefaultFormats
  val messageCounter = new AtomicInteger(0)

  def main(args: Array[String]) {

    val system = ActorSystem("PrintingEchoClient")

    new WebSocket with BufferedWebSocket {
      val uri = URI.create("ws://localhost:8125/")

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
  }
}
