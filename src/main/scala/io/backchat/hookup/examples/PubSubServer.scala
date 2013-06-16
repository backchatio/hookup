package io.backchat.hookup
package examples


import org.json4s._
import java.util.concurrent.{ConcurrentSkipListSet, ConcurrentHashMap}
import collection.JavaConverters._

object PubSubServer {

  private val subscriptions = new ConcurrentHashMap[String, Set[HookupServerClient]]().asScala.withDefaultValue(Set.empty)


  def publish(topic: String, data: JValue) {
    subscriptions(topic) foreach {
      _ ! JArray(JString("publish") :: data :: Nil)
    }
  }

  def subscribe(topic: String, client: HookupServerClient) {
    subscriptions(topic) += client
  }

  def unsubscribe(topic: String, client: HookupServerClient) {
    subscriptions(topic) -= client
  }


  def main(args: Array[String]) {
    val server = HookupServer(ServerInfo("PubSubServer", port = 8128)) {
      new HookupServerClient {
        def receive = {
          case Disconnected(_) ⇒
            subscriptions.keysIterator foreach { subscriptions(_) -= this }
          case Connected ⇒
            println("client connected")
          case TextMessage(_) ⇒
            this send "only json messages are allowed"
          case JsonMessage(JArray(JString(c) :: JString(topic) :: Nil)) if c.equalsIgnoreCase("subscribe") =>
            subscribe(topic, this)
          case JsonMessage(JArray(JString(c) :: JString(topic) :: Nil)) if c.equalsIgnoreCase("unsubscribe") =>
            unsubscribe(topic, this)
          case JsonMessage(JArray(JString(c) :: JString(topic) :: data :: Nil)) if c.equalsIgnoreCase("publish") =>
            publish(topic, data)
        }
      }
    }
    server.start
  }
}
