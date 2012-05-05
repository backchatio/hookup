package io.backchat.websocket

import akka.util.duration._
import net.liftweb.json.JsonAST.JValue
import akka.util.Duration

trait WebSocketInMessage
trait WebSocketOutMessage
trait Ackable { self: WebSocketOutMessage ⇒
  def needsAck(within: Duration = 1 second) = NeedsAck(this, within)
}
case object Connected extends WebSocketInMessage
case object Reconnecting extends WebSocketInMessage
case class JsonMessage(content: JValue) extends WebSocketInMessage with WebSocketOutMessage with Ackable
case class TextMessage(content: String) extends WebSocketInMessage with WebSocketOutMessage with Ackable
case class BinaryMessage(content: Array[Byte]) extends WebSocketInMessage with WebSocketOutMessage
private[websocket] case class NeedsAck(message: Ackable, timeout: Duration = 1 second) extends WebSocketOutMessage
private[websocket] case class AckRequest(message: Ackable, id: Long) extends WebSocketInMessage
case class AckFailed(message: WebSocketOutMessage) extends WebSocketInMessage
private[websocket] case class Ack(id: Long) extends WebSocketInMessage with WebSocketOutMessage
case class Error(cause: Option[Throwable]) extends WebSocketInMessage
case class Disconnected(cause: Option[Throwable]) extends WebSocketInMessage
private[websocket] case object Disconnect extends WebSocketOutMessage

