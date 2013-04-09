package io.backchat.hookup
package examples

import java.net.URI
import scala.concurrent.duration._
import net.liftweb.json._
import JsonDSL._
import java.util.concurrent.atomic.AtomicInteger
import akka.actor.ActorSystem

object PubSubClient {

  private val system = ActorSystem("pubsubclient")

  class PubSubClient(name: String) extends HookupClient {
    val uri = URI.create("ws://localhost:8128/")
    val messageCounter = new AtomicInteger(0)

    val settings: HookupClientConfig = HookupClientConfig(
      uri = uri,
      throttle = IndefiniteThrottle(5 seconds, 30 minutes),
      buffer = None)

    def receive = {
      case Connected =>
        send(List("subscribe", "topic.a"): JValue)
      case TextMessage(text) ⇒
        println(text)
      case JsonMessage(JArray(JString("publish") :: data :: Nil)) =>
        println("received pubsub message")
        println(pretty(render(data)))
    }

    connect() onSuccess {
      case _ ⇒
        println("connected to: %s" format uri.toASCIIString)
        system.scheduler.schedule(2 seconds, 5 second) {
          send(List("publish", "topic.a", name + ": message " + messageCounter.incrementAndGet().toString): JValue)
        }
    }
  }

  def main(args: Array[String]) {
    new PubSubClient(args(0))
  }
}
