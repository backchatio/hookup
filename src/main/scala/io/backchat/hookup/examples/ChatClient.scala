package io.backchat.hookup
package examples

import java.net.URI
import net.liftweb.json.{ DefaultFormats, Formats }
import akka.actor.ActorSystem
import scala.concurrent.duration._
import java.util.concurrent.atomic.AtomicInteger
import java.io.File

object DefaultConversions { 
  implicit def stringToTextMessage(s: String) = TextMessage(s)
}


object ChatClient {

  val messageCounter = new AtomicInteger(0)

  import DefaultConversions._
  //implicit def stringToTextMessage(s: String) = TextMessage(s)

  val system = ActorSystem("ChatClient")

  def makeClient(args: Array[String]) = {
    if (args.isEmpty) {
      sys.error("Specify a name as the argument")
    }
    // val system = ActorSystem("ChatClient")

    val client = new HookupClient {
      val uri = URI.create("ws://localhost:8127/")

      val settings: HookupClientConfig = HookupClientConfig(
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
    client
  }

  def main(args: Array[String]) = makeClient(args)
}
