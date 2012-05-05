package io.backchat.websocket

import net.liftweb.json._
import akka.util.duration._

trait WireFormat {

  def parseInMessage(message: String): WebSocketInMessage

  def parseOutMessage(message: String): WebSocketOutMessage

  def render(message: WebSocketOutMessage): String

}

class SimpleJsonWireFormat(implicit formats: Formats) extends WireFormat {
  private def parseMessage(message: String) = {
    if (message.trim.startsWith("{") || message.trim.startsWith("["))
      parseOpt(message) map (JsonMessage(_)) getOrElse TextMessage(message)
    else TextMessage(message)
  }

  def parseOutMessage(message: String): WebSocketOutMessage = parseMessage(message)

  def parseInMessage(message: String): WebSocketInMessage = parseMessage(message)

  def render(message: WebSocketOutMessage) = message match {
    case TextMessage(text) => text
    case JsonMessage(json) => compact(JsonAST.render(json))
    case _ => ""
  }
}

object JsonProtocolWireFormat {

  object ParseToWebSocketInMessage {

    def apply(message: String)(implicit format: Formats) = inferMessageTypeFromContent(message)

    private def inferMessageTypeFromContent(content: String)(implicit format: Formats): WebSocketInMessage = {
      val possiblyJson = content.trim.startsWith("{") || content.trim.startsWith("[")
      if (!possiblyJson) TextMessage(content)
      else parseOpt(content) map inferJsonMessageFromContent getOrElse TextMessage(content)
    }

    private def inferJsonMessageFromContent(content: JValue)(implicit format: Formats): WebSocketInMessage = {
      val contentType = (content \ "type").extractOpt[String].map(_.toLowerCase) getOrElse "none"
      (contentType) match {
        case "ack_request" ⇒ AckRequest(inferContentMessage(content \ "message"), (content \ "id").extract[Long])
        case "ack" ⇒ Ack((content \ "id").extract[Long])
        case "text" ⇒ TextMessage((content \ "content").extract[String])
        case "json" ⇒ JsonMessage((content \ "content"))
        case _ ⇒ JsonMessage(content)
      }
    }

    private def inferContentMessage(content: JValue)(implicit format: Formats): Ackable = {
      val contentType = (content \ "type").extractOrElse("none")
      (contentType) match {
        case "text" ⇒ TextMessage((content \ "content").extract[String])
        case "json" ⇒ JsonMessage((content \ "content"))
        case "none" ⇒ JsonMessage(content)
      }
    }
  }

  object ParseToWebSocketOutMessage {
    def apply(message: String)(implicit format: Formats): WebSocketOutMessage = inferMessageTypeFromContent(message)

    private def inferMessageTypeFromContent(content: String)(implicit format: Formats): WebSocketOutMessage = {
      val possiblyJson = content.trim.startsWith("{") || content.trim.startsWith("[")
      if (!possiblyJson) TextMessage(content)
      else parseOpt(content) map inferJsonMessageFromContent getOrElse TextMessage(content)
    }

    private def inferJsonMessageFromContent(content: JValue)(implicit format: Formats): WebSocketOutMessage = {
      val contentType = (content \ "type").extractOpt[String].map(_.toLowerCase) getOrElse "none"
      (contentType) match {
        case "needs_ack" ⇒ NeedsAck(inferContentMessage(content \ "content"), (content \ "timeout").extract[Long].millis)
        case "text" ⇒ TextMessage((content \ "content").extract[String])
        case "json" ⇒ JsonMessage((content \ "content"))
        case _ ⇒ JsonMessage(content)
      }
    }

    private def inferContentMessage(content: JValue)(implicit format: Formats): Ackable = content match {
      case JString(text) ⇒ TextMessage(text)
      case _ ⇒
        val contentType = (content \ "type").extractOrElse("none")
        (contentType) match {
          case "text" ⇒ TextMessage((content \ "content").extract[String])
          case "json" ⇒ JsonMessage((content \ "content"))
          case "none" ⇒ JsonMessage(content)
        }
    }
  }

  object RenderOutMessage {

    import JsonDSL._

    def apply(message: WebSocketOutMessage): String = {
      message match {
        case TextMessage(text) ⇒ text
        case JsonMessage(json) ⇒ compact(render(("type" -> "json") ~ ("content" -> json)))
        case NeedsAck(msg, timeout) ⇒
          compact(render(("type" -> "needs_ack") ~ ("timeout" -> timeout.toMillis) ~ ("content" -> contentFrom(msg))))
        case Ack(id) ⇒ compact(render(("type" -> "ack") ~ ("id" -> id)))
        case x ⇒ sys.error(x.getClass.getName + " is an unsupported message type")
      }
    }

    private[this] def contentFrom(message: Ackable): (String, JValue) = message match {
      case TextMessage(text) ⇒ ("text", JString(text))
      case JsonMessage(json) ⇒ ("json", json)
    }
  }

}

class JsonProtocolWireFormat(implicit formats: Formats) extends WireFormat {
  def parseInMessage(message: String): WebSocketInMessage =
    JsonProtocolWireFormat.ParseToWebSocketInMessage(message)

  def parseOutMessage(message: String): WebSocketOutMessage =
    JsonProtocolWireFormat.ParseToWebSocketOutMessage(message)

  def render(message: WebSocketOutMessage) =
    JsonProtocolWireFormat.RenderOutMessage(message)
}