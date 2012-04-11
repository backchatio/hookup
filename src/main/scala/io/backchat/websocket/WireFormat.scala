package io.backchat.websocket

import net.liftweb.json._

trait WireFormat {

  def parseInMessage(message: String): WebSocketInMessage
  def parseOutMessage(message: String): WebSocketOutMessage
  def render(message: WebSocketOutMessage): String

}

class LiftJsonWireFormat(implicit formats: Formats) extends WireFormat {
  def parseInMessage(message: String) = WebSocket.ParseToWebSocketInMessage(message)

  def parseOutMessage(message: String) = WebSocket.ParseToWebSocketOutMessage(message)

  def render(message: WebSocketOutMessage) = WebSocket.RenderOutMessage(message)
}