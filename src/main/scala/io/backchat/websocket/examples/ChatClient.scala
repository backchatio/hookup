package io.backchat.websocket
package examples

import java.net.URI
import net.liftweb.json.{ DefaultFormats, Formats }
import akka.actor.ActorSystem
import akka.util.duration._
import java.util.concurrent.atomic.AtomicInteger
import java.io.File

object ChatClient {

  implicit val wireFormat: WireFormat = new JsonProtocolWireFormat()(DefaultFormats)
  val messageCounter = new AtomicInteger(0)

  def main(args: Array[String]) {

    if (args.isEmpty) {
      sys.error("Specify a name as the argument")
    }
    val system = ActorSystem("ChatClient")

    new WebSocket {
      val uri = URI.create("ws://localhost:8127/")

      val settings: WebSocketContext = WebSocketContext(
        uri = uri,
        throttle = IndefiniteThrottle(5 seconds, 30 minutes),
        buffer = Some(new FileBuffer(new File("./work/buffer.log"))))

      def receive = {
        case TextMessage(text) ⇒
          println(text)
      }

      connect() onSuccess {
        case _ ⇒
          println("connected to: %s" format uri.toASCIIString)
          system.scheduler.schedule(2 seconds, 5 second) {
            send(args(0) + ": message " + messageCounter.incrementAndGet().toString)
          }
      }
    }
  }
}
