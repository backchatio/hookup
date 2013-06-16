package io.backchat.hookup
package examples

import org.json4s._
import org.json4s.jackson.JsonMethods._

object PrintAllEventsServer {
  import DefaultConversions._

  def main(args: Array[String]) {

    val server = HookupServer(8126) {
      /// code_ref: all_events
      new HookupServerClient {
        def receive = {
          case Connected ⇒
            println("client connected")
          case Disconnected(_) ⇒
            println("client disconnected")
          case m @ Error(exOpt) ⇒
            System.err.println("Received an error: " + m)
            exOpt foreach { _.printStackTrace(System.err) }
          case m: TextMessage ⇒
            println(m)
            send(m)
          case m: JsonMessage ⇒
            println("JsonMessage(" + pretty(render(m.content)) + ")")
            send(m)
        }
      }
      /// end_code_ref
    }

    server onStart {
      println("Server is starting")
    }
    server onStop {
      println("Server is stopping")
    }
    server.start
  }

}
