package io.backchat.websocket
package examples

import net.liftweb.json._
import java.util.concurrent.atomic.AtomicInteger
import java.net.URI
import akka.util.duration._
import akka.actor.{Cancellable, ActorSystem}
import JsonDSL._

object PrintAllEventsClient {

  implicit val formats: Formats = DefaultFormats
  val messageCounter = new AtomicInteger(0)
  val bufferedCounter = new AtomicInteger(0)

  def main(args: Array[String]) {

    val system = ActorSystem("PrintingEchoClient")
    var timeout: Cancellable = null

    new WebSocket with BufferedWebSocket {
      val uri = URI.create("ws://localhost:8126/")

      def receive = {
        case Connected => println("Connected to the server")
        case Reconnecting => println("Reconnecting")
        case Disconnected(_) => println("disconnected from the server")
        case m @ Error(exOpt) =>
          System.err.println("Received an error: " + m)
          exOpt foreach { _.printStackTrace(System.err) }
        case m: TextMessage =>
          println("RECV: " + m)
        case m: JsonMessage =>
          println("RECV: JsonMessage(" + pretty(render(m.content)) + ")")
      }

      connect() onSuccess {
        case _ =>
          println("connected to: %s" format uri.toASCIIString)
          timeout = system.scheduler.schedule(0 seconds, 1 second) {
            if (isConnected) {
              val newCount = messageCounter.incrementAndGet()
              if (newCount <= 10) {
                if(newCount % 2 != 0) send("message " + newCount.toString)  // send a text message
                else send((("data" -> ("message" -> newCount))) : JValue)  // send a json message
              } else  {
                println("Disconnecting after 10 messages")
                if (timeout != null) timeout.cancel()
                close() onComplete {
                  case _  =>
                    println("All resources have been closed")
                    sys.exit()
                }
              }
            } else {
              val newCount = bufferedCounter.incrementAndGet()
              if(newCount % 2 != 0) send("buffered message " + newCount.toString)  // send a text message
              else send((("data" -> (("message" -> newCount) ~ ("extra" -> "buffered")))) : JValue)  // send a json message
            }
          }


      }
    }
  }

}
