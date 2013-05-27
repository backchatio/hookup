package io.backchat.hookup

import scala.concurrent.Future

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
   * @param message A [[io.backchat.hookup.OutboundMessage]] message
   * @return An [[scala.concurrent.Future]] of [[io.backchat.hookup.OperationResult]]
   */
  def send(message: OutboundMessage): Future[OperationResult]

  /**
   * Disconnect from the socket, perform closing handshake if necessary
   * @return An [[scala.concurrent.Future]] of [[io.backchat.hookup.OperationResult]]
   */
  def disconnect(): Future[OperationResult]
}
