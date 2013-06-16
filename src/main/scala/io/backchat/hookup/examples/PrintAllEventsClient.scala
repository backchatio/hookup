package io.backchat.hookup
package examples

import org.json4s._
import java.util.concurrent.atomic.AtomicInteger
import java.net.URI
import scala.concurrent.duration._
import akka.actor.{ Cancellable, ActorSystem }
import JsonDSL._
import java.io.File
import org.json4s.jackson.JsonMethods._

object PrintAllEventsClient {
  import DefaultConversions._

  val messageCounter = new AtomicInteger(0)
  val bufferedCounter = new AtomicInteger(0)

  def main(args: Array[String]) {

    val system = ActorSystem("PrintAllEventsClient") // the actor system is only for the scheduler in the example
    var timeout: Cancellable = null

    new HookupClient {
      val uri = URI.create("ws://localhost:8126/")

      val settings: HookupClientConfig = HookupClientConfig(
        uri,
        throttle = IndefiniteThrottle(5 seconds, 30 minutes),
        buffer = Some(new FileBuffer(new File("./work/buffer.log"))))

      def receive = {
        case Connected       ⇒ println("Connected to the server")
        case Reconnecting    ⇒ println("Reconnecting")
        case Disconnected(_) ⇒ println("disconnected from the server")
        case m @ Error(exOpt) ⇒
          System.err.println("Received an error: " + m)
          exOpt foreach { _.printStackTrace(System.err) }
        case m: TextMessage ⇒
          println("RECV: " + m)
        case m: JsonMessage ⇒
          println("RECV: JsonMessage(" + pretty(render(m.content)) + ")")
      }

      connect() onSuccess {
        case _ ⇒
          // At this point we're fully connected and the handshake has completed
          println("connected to: %s" format uri.toASCIIString)
          timeout = system.scheduler.schedule(0 seconds, 1 second) {
            if (isConnected) { // if we are still connected when this executes then just send a message to the socket
              val newCount = messageCounter.incrementAndGet()
              if (newCount <= 10) {
                if (newCount % 2 != 0) send("message " + newCount.toString) // send a text message
                else send((("data" -> ("message" -> newCount))): JValue) // send a json message
              } else { // if we reach 10 messages disconnect from the server
                println("Disconnecting after 10 messages")
                if (timeout != null) timeout.cancel()
                disconnect() onComplete {
                  case _ ⇒
                    println("All resources have been closed")
                    sys.exit()
                }
              }
            } else {
              // we've lost connection from the server so buffer messages and
              // employ a backoff strategy until the server comes back
              val newCount = bufferedCounter.incrementAndGet()
              if (newCount % 2 != 0) send("buffered message " + newCount.toString) // send a text message
              else send((("data" -> (("message" -> newCount) ~ ("extra" -> "buffered")))): JValue) // send a json message
            }
          }

      }
    }
  }

}
