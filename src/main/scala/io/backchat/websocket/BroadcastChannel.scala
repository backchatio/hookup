package io.backchat.websocket

import akka.dispatch.Future

trait BroadcastChannel extends BroadcastChannelLike {
  def id: Int
}

trait BroadcastChannelLike {
  def send(message: WebSocketOutMessage): Future[OperationResult]
  def disconnect(): Future[OperationResult]
}
