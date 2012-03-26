package io.backchat

import websocket.WebSocketServer.BroadcastChannel
import org.jboss.netty.channel.{ChannelFutureListener, ChannelFuture}
import akka.dispatch.{ExecutionContext, Await, Promise, Future}


package object websocket {

  private[websocket] implicit def string2richerString(s: String) = new {
    def blankOption = if (isBlank) None else Some(s)
    def isBlank = s == null || s.trim.isEmpty
    def nonBlank = !isBlank
  }

  private[websocket] implicit def option2richerOption[T](opt: Option[T]) = new {
    def `|`(other: => T): T = opt getOrElse other
  }

  implicit def fn2BroadcastFilter(fn: WebSocketServer.BroadcastChannel => Boolean): WebSocketServer.BroadcastFilter = {
    new WebSocketServer.BroadcastFilter {
      def apply(v1: BroadcastChannel) = fn(v1)
    }
  }



  sealed trait OperationResult
  case object Success extends OperationResult
  case object Cancelled extends OperationResult
  case class ResultList(results: List[OperationResult]) extends OperationResult

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
}