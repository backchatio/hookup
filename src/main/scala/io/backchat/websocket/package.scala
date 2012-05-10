package io.backchat

import akka.dispatch.{ ExecutionContext, Promise, Future }
import org.jboss.netty.channel.{ Channel, ChannelFutureListener, ChannelFuture }
import net.liftweb.json.JsonAST.JValue
import akka.util.Duration
import java.util.concurrent.TimeUnit

/**
 * The package object for the library.
 * This contains some implicit conversions to smooth the api over and allow for a single import
 * at the top of a file to get the api working.
 */
package object websocket {

  private[websocket] implicit def string2richerString(s: String) = new {
    def blankOption = if (isBlank) None else Some(s)
    def isBlank = s == null || s.trim.isEmpty
    def nonBlank = !isBlank
  }

  private[websocket] implicit def option2richerOption[T](opt: Option[T]) = new {
    def `|`(other: ⇒ T): T = opt getOrElse other
  }

  private[websocket] implicit def richerDuration(duration: Duration) = new {
    def doubled = Duration(duration.toMillis * 2, TimeUnit.MILLISECONDS)

    def max(upperBound: Duration) = if (duration > upperBound) upperBound else duration
  }

  /**
   * An implicit conversion from a predicate function that takes a [[io.backchat.websocket.BroadcastChannel]] to
   * a [[io.backchat.websocket.WebSocketServer.BroadcastFilter]]
   *
   * @param fn The predicate function to convert to a filter
   * @return A [[io.backchat.websocket.WebSocketServer.BroadcastFilter]]
   */
  implicit def fn2BroadcastFilter(fn: BroadcastChannel ⇒ Boolean): WebSocketServer.BroadcastFilter = {
    new WebSocketServer.BroadcastFilter {
      def apply(v1: BroadcastChannel) = fn(v1)
    }
  }

  /**
   * An implicit conversion from a [[org.jboss.netty.channel.ChannelFuture]] to an [[akka.dispatch.Future]]
   * @param fut The [[org.jboss.netty.channel.ChannelFuture]]
   * @return A [[akka.dispatch.Future]]
   */
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

  /**
   * Implicit conversion from a regular string to a [[io.backchat.websocket.TextMessage]]
   *
   * @param content The string content of the message
   * @return A [[io.backchat.websocket.TextMessage]]
   */
  implicit def string2TextMessage(content: String): WebSocketOutMessage with Ackable = TextMessage(content)

  /**
   * Implicit conversion from a lift-json jvalue to a [[io.backchat.websocket.JsonMessage]]
   *
   * @param content The string content of the message
   * @return A [[io.backchat.websocket.JsonMessage]]
   */
  implicit def jvalue2JsonMessage(content: JValue): WebSocketOutMessage with Ackable = JsonMessage(content)

  /**
   * Type forwarder for a websocket server client
   */
  type WebSocketServerClient = WebSocketServer.WebSocketServerClient

  private[websocket] implicit def nettyChannel2BroadcastChannel(ch: Channel)(implicit executionContext: ExecutionContext): BroadcastChannel =
    new { val id: Int = ch.getId } with BroadcastChannel {
      def send(msg: WebSocketOutMessage) = ch.write(msg).toAkkaFuture
      def disconnect() = ch.close().toAkkaFuture
    }

}