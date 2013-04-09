package io.backchat.hookup
import net.liftweb.json._
import scala.concurrent.duration._

/**
 * The interface trait for a wire format.
 * Creating a new wire format means implementing these 3 methods.
 */
trait WireFormat {

  /**
   * The name of this wire format
   * @return The name
   */
  def name: String

  /**
   * A flag to indicate whether this wireformat supports acking or not
   * @return True if this wire format supports acking, otherwise false
   */
  def supportsAck: Boolean

  /**
   * Parse an inbound message from a string. This is used when a message is received over a connection.
   *
   * @param message The serialized message to parse
   * @return the resulting [[io.backchat.hookup.InboundMessage]]
   */
  def parseInMessage(message: String): InboundMessage

  /**
   * Parse an outbound message from a string. This is used when the buffer is being drained.
   *
   * @param message The serialized message to parse
   * @return the resulting [[io.backchat.hookup.OutboundMessage]]
   */
  def parseOutMessage(message: String): OutboundMessage

  /**
   * Render an outbound message to string. This is used when a message is sent to the remote party.
   *
   * @param message The message to serialize
   * @return The string representation of the message
   */
  def render(message: OutboundMessage): String

}

/**
 * A protocol format that is just plain and simple json. This protocol doesn't support acking.
 * It looks at the first character in the message and if it thinks it's JSON it will try to parse it as JSON
 * otherwise it creates a text message
 *
 * @param formats the [[net.liftweb.json.Formats]] for lift-json
 */
class SimpleJsonWireFormat(implicit formats: Formats) extends WireFormat {

  val name = "simpleJson"
  val supportsAck = false

  private[this] def parseMessage(message: String) = {
    if (message.trim.startsWith("{") || message.trim.startsWith("["))
      parseOpt(message) map (JsonMessage(_)) getOrElse TextMessage(message)
    else TextMessage(message)
  }

  def parseOutMessage(message: String): OutboundMessage = parseMessage(message)

  def parseInMessage(message: String): InboundMessage = parseMessage(message)

  def render(message: OutboundMessage) = message match {
    case TextMessage(text) => text
    case JsonMessage(json) => compact(JsonAST.render(json))
    case _ => ""
  }
}

/**
 * @see [[io.backchat.hookup.JsonProtocolWireFormat]]
 */
object JsonProtocolWireFormat {

  private object ParseToWebSocketInMessage {

    def apply(message: String)(implicit format: Formats) = inferMessageTypeFromContent(message)

    private def inferMessageTypeFromContent(content: String)(implicit format: Formats): InboundMessage = {
      val possiblyJson = content.trim.startsWith("{") || content.trim.startsWith("[")
      if (!possiblyJson) TextMessage(content)
      else parseOpt(content) map inferJsonMessageFromContent getOrElse TextMessage(content)
    }

    private def inferJsonMessageFromContent(content: JValue)(implicit format: Formats) = {
      val contentType = (content \ "type").extractOpt[String].map(_.toLowerCase) getOrElse "none"
      (contentType) match {
        case "ack_request" ⇒ AckRequest(inferContentMessage((content \ "content")), (content \ "id").extract[Long])
        case "ack" ⇒ Ack((content \ "id").extract[Long])
        case "text" ⇒ TextMessage((content \ "content").extract[String])
        case "json" ⇒ JsonMessage((content \ "content"))
        case _ ⇒ JsonMessage(content)
      }
    }

    private def inferContentMessage(content: JValue)(implicit format: Formats): Ackable = {
      content match {
        case JString(text) ⇒ TextMessage(text)
        case _ =>
          val contentType = (content \ "type").extractOrElse("none")
          (contentType) match {
            case "text" ⇒ TextMessage((content \ "content").extract[String])
            case "json" ⇒ JsonMessage((content \ "content"))
            case "none" ⇒ content match {
              case JString(text) =>
                val possiblyJson = text.trim.startsWith("{") || text.trim.startsWith("[")
                if (!possiblyJson) TextMessage(text)
                else parseOpt(text) map inferContentMessage getOrElse TextMessage(text)
              case jv => JsonMessage(content)
            }
          }
      }
    }
  }

  private object ParseToWebSocketOutMessage {
    def apply(message: String)(implicit format: Formats): OutboundMessage = inferMessageTypeFromContent(message)

    private def inferMessageTypeFromContent(content: String)(implicit format: Formats): OutboundMessage = {
      val possiblyJson = content.trim.startsWith("{") || content.trim.startsWith("[")
      if (!possiblyJson) TextMessage(content)
      else parseOpt(content) map inferJsonMessageFromContent getOrElse TextMessage(content)
    }

    private def inferJsonMessageFromContent(content: JValue)(implicit format: Formats): OutboundMessage = {
      val contentType = (content \ "type").extractOpt[String].map(_.toLowerCase) getOrElse "none"
      (contentType) match {
        case "ack" => Ack((content \ "id").extract[Long])
        case "needs_ack" ⇒ NeedsAck(inferContentMessage(content \ "content"), (content \ "timeout").extract[Long].millis)
        case "text" ⇒ TextMessage((content \ "content").extract[String])
        case "json" ⇒ JsonMessage((content \ "content"))
        case _ ⇒ JsonMessage(content)
      }
    }

    private def inferContentMessage(content: JValue)(implicit format: Formats): Ackable = content match {
      case JString(text) ⇒ TextMessage(text)
      case _ =>
        val contentType = (content \ "type").extractOrElse("none")
        (contentType) match {
          case "text" ⇒ TextMessage((content \ "content").extract[String])
          case "json" ⇒ JsonMessage((content \ "content"))
          case "none" ⇒ content match {
            case JString(text) =>
              val possiblyJson = text.trim.startsWith("{") || text.trim.startsWith("[")
              if (!possiblyJson) TextMessage(text)
              else parseOpt(text) map inferContentMessage getOrElse TextMessage(text)
            case jv => JsonMessage(content)
          }
        }
    }
  }

  private object RenderOutMessage {

    import JsonDSL._

    def apply(message: OutboundMessage): String = {
      message match {
        case Ack(id) ⇒ compact(render(("type" -> "ack") ~ ("id" -> id)))
        case m: TextMessage ⇒ compact(render(contentFrom(m)))
        case m: JsonMessage ⇒ compact(render(contentFrom(m)))
        case NeedsAck(msg, timeout) ⇒
          compact(render(("type" -> "needs_ack") ~ ("timeout" -> timeout.toMillis) ~ ("content" -> contentFrom(msg))))
        case x ⇒ sys.error(x.getClass.getName + " is an unsupported message type")
      }
    }

    private[this] def contentFrom(message: Ackable): JValue = message match {
      case TextMessage(text) ⇒ ("type" -> "text") ~ ("content" -> text)
      case JsonMessage(json) ⇒ ("type" -> "json") ~ ("content" -> json)
    }
  }

}

/**
 * A protocol that supports all the features of the websocket server.
 * This wireformat knows about acking and the related protocol messages.
 * it uses a json object to transfer meaning everything has a property name.
 *
 * @param formats  the [[net.liftweb.json.Formats]] for lift-json
 */
class JsonProtocolWireFormat(implicit formats: Formats) extends WireFormat {
  val name = "jsonProtocol"
  val supportsAck = true
  import JsonProtocolWireFormat._
  def parseInMessage(message: String): InboundMessage = ParseToWebSocketInMessage(message)
  def parseOutMessage(message: String): OutboundMessage = ParseToWebSocketOutMessage(message)
  def render(message: OutboundMessage) = RenderOutMessage(message)
}
