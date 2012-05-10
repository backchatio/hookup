package io.backchat.websocket

import akka.dispatch.Future

/**
 * A broadcast channel represents a connection
 */
trait BroadcastChannel extends BroadcastChannelLike {
  /**
   * @return The connection id
   */
  def id: Int
}

/**
 * Broadcast channel like is either a connection or a proxy for a connection.
 * It contains the methods for sending and disconnecting from a socket
 */
trait BroadcastChannelLike {

  /**
   * Send a message over the current connection
   * @param message A [[io.backchat.websocket.WebSocketOutMessage]] message
   * @return An [[akka.dispatch.Future]] of [[io.backchat.websocket.OperationResult]]
   */
  def send(message: WebSocketOutMessage): Future[OperationResult]

  /**
   * Disconnect from the socket, perform closing handshake if necessary
   * @return An [[akka.dispatch.Future]] of [[io.backchat.websocket.OperationResult]]
   */
  def disconnect(): Future[OperationResult]
}
