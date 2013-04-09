package io.backchat.hookup

import scala.concurrent.duration._
import net.liftweb.json.JsonAST.JValue
import scala.concurrent.duration.Duration

/**
 * A marker trait for inbound messages
 */
sealed trait InboundMessage

/**
 * A marker trait for outbound messages
 */
sealed trait OutboundMessage

/**
 * Adds acking support to a message
 * This can only be included in a websocket out message
 */
trait Ackable { self: OutboundMessage ⇒

  /**
   * Request that this message will be acked upon receipt by the server.
   *
   * @param within An [[scala.concurrent.duration.Duration]] representing the timeout for the ack
   * @return A [[io.backchat.hookup.OutboundMessage]] with this message wrapped in a [[io.backchat.hookup.NeedsAck]] envelope
   */
  def needsAck(within: Duration = 1 second): OutboundMessage = NeedsAck(this, within)
}

/**
 * A base trait for creating messages of different content types
 * @tparam T The type of content this protocol message represents
 */
trait ProtocolMessage[T] extends InboundMessage with OutboundMessage with Ackable {
  def content: T
}

/**
 * A callback event signaling that the connection has been fully established.
 * This means that any handshakes have been completed successfully too.
 *
 * When you receive this callback message you can be sure there is someone on the other end.
 */
case object Connected extends InboundMessage

/**
 * A callback event signaling that the connection to the server has been broken and the client
 * is trying to reconnect. Every reconnect attempt fires this message.
 *
 * Typically you don't need to do anything when this happens, if you use a backoff like
 * [[io.backchat.hookup.IndefiniteThrottle]] then the client does the reconnection bit automatically, it's only then
 * that you can expect these events.
 */
case object Reconnecting extends InboundMessage

/**
 * A message representing a json object sent to/received from a remote party.
 *
 * @param content A [[net.liftweb.json.JValue]] object
 */
case class JsonMessage(content: JValue) extends ProtocolMessage[JValue]

/**
 * A message representing a text object sent to/received from a remote party.
 *
 * @param content A [[scala.Predef.String]] representing the content of the message
 */
case class TextMessage(content: String) extends ProtocolMessage[String]

/**
 * A message representing an array of bytes sent to/received from a remote party.
 *
 * @param content An Array of Bytes representing the content of the message
 */
case class BinaryMessage(content: Array[Byte]) extends ProtocolMessage[Array[Byte]]

/**
 * A message envelope to request acking for an outbound message
 *
 * @param message The [[io.backchat.hookup.Ackable]] message to be acknowledged
 * @param timeout An [[scala.concurrent.duration.Duration]] specifying the timeout for the operation
 */
private[hookup] case class NeedsAck(message: Ackable, timeout: Duration = 1 second) extends OutboundMessage

/**
 * An Inbound message for an ack operation, this is an implementation detail and not visible to the library user
 *
 * @param message The [[io.backchat.hookup.Ackable]] message to be acknowledged
 * @param id The id of the ack operation
 */
private[hookup] case class AckRequest(message: Ackable, id: Long) extends InboundMessage

/**
 * A callback event signaling failure of an ack request.
 * This is not handled automatically and you have to decide what you want to do with the message,
 * you could send it again, send it somewhere else, drop it ...
 *
 * @param message An [[io.backchat.hookup.OutboundMessage]] outbound message
 */
case class AckFailed(message: OutboundMessage) extends InboundMessage

private[hookup] case class Ack(id: Long) extends InboundMessage with OutboundMessage
private[hookup] case class SelectedWireFormat(wireFormat: WireFormat) extends InboundMessage

/**
 * A callback event signaling that an error has occurred. if the error was an exception thrown
 * then the cause object will be filled in.
 *
 * @param cause A [[scala.Option]] of [[java.lang.Throwable]]
 */
case class Error(cause: Option[Throwable]) extends InboundMessage

/**
 * A callback event signaling that the connection has ended, if the cause was an exception thrown
 * then the cause object will be filled in.
 *
 * @param cause A [[scala.Option]] of [[java.lang.Throwable]]
 */
case class Disconnected(cause: Option[Throwable]) extends InboundMessage

private[hookup] case object Disconnect extends OutboundMessage

