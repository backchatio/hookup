package io.backchat

import akka.dispatch.{ ExecutionContext, Promise, Future }
import org.jboss.netty.channel.{ Channel, ChannelFutureListener, ChannelFuture }
import net.liftweb.json.JsonAST.JValue
import akka.util.Duration
import java.util.concurrent.TimeUnit
import org.jboss.netty.logging.{Slf4JLoggerFactory, InternalLoggerFactory}
import net.liftweb.json.DefaultFormats
import reflect.BeanProperty
import org.joda.time.DateTimeZone
import org.joda.time.format.DateTimeFormat
import org.jboss.netty.util.CharsetUtil

/**
 * The package object for the library.
 * This contains some implicit conversions to smooth the api over and allow for a single import
 * at the top of a file to get the api working.
 */
package object hookup {



  val HttpDateGMT = "GMT"
  val HttpDateTimeZone = DateTimeZone.forID(HttpDateGMT)
  val HttpDateFormat = DateTimeFormat.forPattern("EEE, dd MMM yyyy HH:mm:ss zzz").withZone(HttpDateTimeZone)
  val HttpCacheSeconds = 60

  private[hookup] val Utf8 = CharsetUtil.UTF_8

  /// code_ref: default_protocols
  val SimpleJson = new SimpleJsonWireFormat()(DefaultFormats)
  val JsonProtocol = new JsonProtocolWireFormat()(DefaultFormats)
  /**
   * The default protocols hookup understands
   */
  @BeanProperty
  val DefaultProtocols = Seq(SimpleJson, JsonProtocol)
  /// end_code_ref

  /**
   * The default protocol for the hookup server to use. By default this a simple json protocol that doesn't support
   * any advanced features but just sends json messages back and forth
   */
  @BeanProperty
  var DefaultProtocol = "simpleJson"

  private[hookup] implicit def string2richerString(s: String) = new {
    def blankOption = if (isBlank) None else Some(s)
    def isBlank = s == null || s.trim.isEmpty
    def nonBlank = !isBlank
  }

  private[hookup] implicit def option2richerOption[T](opt: Option[T]) = new {
    def `|`(other: ⇒ T): T = opt getOrElse other
  }

  private[hookup] implicit def richerDuration(duration: Duration) = new {
    def doubled = Duration(duration.toMillis * 2, TimeUnit.MILLISECONDS)

    def max(upperBound: Duration) = if (duration > upperBound) upperBound else duration
  }

  /**
   * An implicit conversion from a predicate function that takes a [[io.backchat.hookup.BroadcastChannel]] to
   * a [[io.backchat.hookup.HookupServer.BroadcastFilter]]
   *
   * @param fn The predicate function to convert to a filter
   * @return A [[io.backchat.hookup.HookupServer.BroadcastFilter]]
   */
  implicit def fn2BroadcastFilter(fn: BroadcastChannel ⇒ Boolean): HookupServer.BroadcastFilter = {
    new HookupServer.BroadcastFilter {
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
   * Implicit conversion from a regular string to a [[io.backchat.hookup.TextMessage]]
   *
   * @param content The string content of the message
   * @return A [[io.backchat.hookup.TextMessage]]
   */
  implicit def string2TextMessage(content: String): OutboundMessage with Ackable = TextMessage(content)

  /**
   * Implicit conversion from a lift-json jvalue to a [[io.backchat.hookup.JsonMessage]]
   *
   * @param content The string content of the message
   * @return A [[io.backchat.hookup.JsonMessage]]
   */
  implicit def jvalue2JsonMessage(content: JValue): OutboundMessage with Ackable = JsonMessage(content)

  /**
   * Type forwarder for a websocket server client
   */
  type HookupServerClient = HookupServer.HookupServerClient

  private[hookup] implicit def nettyChannel2BroadcastChannel(ch: Channel)(implicit executionContext: ExecutionContext): BroadcastChannel =
    new { val id: Int = ch.getId } with BroadcastChannel {
      def send(msg: OutboundMessage) = ch.write(msg).toAkkaFuture
      def disconnect() = ch.close().toAkkaFuture
    }

}