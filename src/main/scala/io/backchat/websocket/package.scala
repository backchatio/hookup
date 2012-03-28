package io.backchat

import akka.dispatch.{ExecutionContext, Await, Promise, Future}
import akka.util.duration._
import org.jboss.netty.channel.{Channel, ChannelFutureListener, ChannelFuture}
import java.util.concurrent.atomic.AtomicLong
import net.liftweb.json.JsonAST.JValue
import akka.util.Duration
import java.util.concurrent.TimeUnit

package object websocket {

  private[websocket] implicit def string2richerString(s: String) = new {
    def blankOption = if (isBlank) None else Some(s)
    def isBlank = s == null || s.trim.isEmpty
    def nonBlank = !isBlank
  }

  private[websocket] implicit def option2richerOption[T](opt: Option[T]) = new {
    def `|`(other: => T): T = opt getOrElse other
  }

  private[websocket] implicit def richerDuration(duration: Duration) = new {
      def doubled = Duration(duration.toMillis * 2, TimeUnit.MILLISECONDS)

      def max(upperBound: Duration) = if (duration > upperBound) upperBound else duration
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

  implicit def string2TextMessage(content: String): WebSocketOutMessage with Ackable = TextMessage(content)
  implicit def jvalue2JsonMessage(content: JValue): WebSocketOutMessage with Ackable = JsonMessage(content)



  private[websocket] implicit def nettyChannel2BroadcastChannel(ch: Channel)(implicit executionContext: ExecutionContext): BroadcastChannel =
    new { val id: Int = ch.getId } with BroadcastChannel {
      def send(msg: WebSocketOutMessage) = ch.write(msg).toAkkaFuture
      def close() = ch.close().toAkkaFuture
    }

}