package io.backchat.websocket.examples

import java.net.URI
import io.backchat.websocket.{TextMessage, BufferedWebSocket, WebSocket}
import net.liftweb.json.{DefaultFormats, Formats}
import akka.actor.ActorSystem
import akka.util.duration._

object PrintingEchoClient {

  implicit val formats: Formats = DefaultFormats

  def main(args: Array[String]) {

    val system = ActorSystem("PrintingEchoClient")

    new WebSocket with BufferedWebSocket {
      val uri = URI.create("ws://localhost:8125/")

      def receive = {
        case TextMessage(text) =>
          println("RECV: " + text)
      }

      connect() onSuccess {
        case _ =>
          println("connected to: %s" format uri.toASCIIString)
          system.scheduler.schedule(0 seconds, 1 second) {
            send("hello")
          }
      }
    }
  }
}
