package io.backchat

import akka.dispatch.{ExecutionContext, Await, Promise, Future}
import org.jboss.netty.channel.{Channel, ChannelFutureListener, ChannelFuture}

package websocket {
  sealed trait OperationResult
  case object Success extends OperationResult
  case object Cancelled extends OperationResult
  case class ResultList(results: List[OperationResult]) extends OperationResult

  trait BroadcastChannel extends BroadcastChannelLike {
    def id: Int
  }

  trait BroadcastChannelLike {
    def send(message: WebSocketOutMessage): Future[OperationResult]
    def close(): Future[OperationResult]
  }

  sealed trait WebSocketInMessage
  sealed trait WebSocketOutMessage

  case object Connected extends WebSocketInMessage
  case class TextMessage(content: String) extends WebSocketInMessage with WebSocketOutMessage
  case class BinaryMessage(content: Array[Byte]) extends WebSocketInMessage with WebSocketOutMessage
  case class Error(cause: Option[Throwable]) extends WebSocketInMessage
  case class Disconnected(cause: Option[Throwable]) extends WebSocketInMessage
  case object Disconnect extends WebSocketOutMessage


}

package object websocket {

  private[websocket] implicit def string2richerString(s: String) = new {
    def blankOption = if (isBlank) None else Some(s)
    def isBlank = s == null || s.trim.isEmpty
    def nonBlank = !isBlank
  }

  private[websocket] implicit def option2richerOption[T](opt: Option[T]) = new {
    def `|`(other: => T): T = opt getOrElse other
  }

  implicit def fn2BroadcastFilter(fn: BroadcastChannel => Boolean): WebSocketServer.BroadcastFilter = {
    new WebSocketServer.BroadcastFilter {
      def apply(v1: BroadcastChannel) = fn(v1)
    }
  }




  implicit def channelFutureToAkkaFuture(fut: ChannelFuture) = new {

    def toAkkaFuture(implicit context: ExecutionContext): Future[OperationResult] = {
      val res = Promise[OperationResult]()
      fut.addListener(new ChannelFutureListener {
        def operationComplete(future: ChannelFuture) {
          if (future.isSuccess) {
            res.success(Success)
          } else if (fut.isCancelled) {
            res.success(Cancelled)
          } else {
            res.failure(future.getCause)
          }
        }
      })
      res
    }
  }


  private[websocket] implicit def nettyChannel2BroadcastChannel(ch: Channel)(implicit executionContext: ExecutionContext): BroadcastChannel =
    new { val id: Int = ch.getId } with BroadcastChannel {
      def send(msg: WebSocketOutMessage) = ch.write(msg).toAkkaFuture
      def close() = ch.close().toAkkaFuture
    }

}