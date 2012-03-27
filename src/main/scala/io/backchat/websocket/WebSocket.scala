package io.backchat.websocket

import org.jboss.netty.bootstrap.ClientBootstrap
import org.jboss.netty.channel._
import socket.nio.NioClientSocketChannelFactory
import org.jboss.netty.handler.codec.http._
import collection.JavaConverters._
import websocketx._
import java.net.{InetSocketAddress, URI}
import java.nio.charset.Charset
import org.jboss.netty.buffer.ChannelBuffers
import akka.util.duration._
import java.util.concurrent.{ConcurrentSkipListSet, Executors}
import akka.dispatch.{ExecutionContext, Await, Promise, Future}
import akka.jsr166y.ForkJoinPool
import java.lang.Thread.UncaughtExceptionHandler
import org.jboss.netty.handler.logging.LoggingHandler
import akka.util.Timeout
import org.jboss.netty.util.{HashedWheelTimer, CharsetUtil}
import org.jboss.netty.handler.timeout.{IdleStateAwareChannelHandler, IdleStateEvent, IdleState, IdleStateHandler}
import org.jboss.netty.logging.{InternalLogger, InternalLogLevel, InternalLoggerFactory}
import java.util.concurrent.atomic.AtomicLong
import net.liftweb.json.{DefaultFormats, Formats}
import io.backchat.websocket.WebSocketServer.MessageAckingHandler


/**
* Usage of the simple websocket client:
*
* <pre>
*   new WebSocket {
*     val uri = new URI("ws://localhost:8080/thesocket")
*
*     def receive = {
*       case Disconnected(_) => println("The websocket to " + url.toASCIIString + " disconnected.")
*       case TextMessage(message) => {
*         println("RECV: " + message)
*         send("ECHO: " + message)
*       }
*     }
*
*    connect() onSuccess {
 *    case Success =>
 *      println("The websocket to " + url.toASCIIString + "is connected.")
 *    case _ =>
 *   }
*  }
* </pre>
*/
object WebSocket {


  type Receive = PartialFunction[WebSocketInMessage, Unit]

  private val logger = InternalLoggerFactory.getInstance("WebSocket")

  class WebSocketClientHostHandler(handshaker: WebSocketClientHandshaker, host: WebSocketHost)(implicit formats: Formats) extends SimpleChannelHandler {
    import net.liftweb.json._
    import JsonDSL._
    private val msgCount = new AtomicLong(0)

    override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
      e.getMessage match {
        case resp: HttpResponse if handshaker.isHandshakeComplete =>
          throw new WebSocketException("Unexpected HttpResponse (status=" + resp.getStatus + ", content="
                              + resp.getContent.toString(CharsetUtil.UTF_8) + ")")
        case resp: HttpResponse =>
          handshaker.finishHandshake(ctx.getChannel, resp)
          host.receive lift Connected

        case f: TextWebSocketFrame =>
          val inferred = inferMessageTypeFromContent(f.getText)
          inferred match {
            case a: Ack => Channels.fireMessageReceived(ctx, a)
            case a: AckRequest => Channels.fireMessageReceived(ctx, a)
            case r => host.receive lift r
          }
        case f: BinaryWebSocketFrame => host.receive lift BinaryMessage(f.getBinaryData.array())
        case f: ContinuationWebSocketFrame =>
          logger warn "Got a continuation frame this is not (yet) supported"
        case _: PingWebSocketFrame => ctx.getChannel.write(new PongWebSocketFrame())
        case _: PongWebSocketFrame =>
        case _: CloseWebSocketFrame => host.close()
      }
    }

    private def inferMessageTypeFromContent(content: String): WebSocketInMessage = {
      val possiblyJson = content.trim.startsWith("{") || content.trim.startsWith("[")
      if (!possiblyJson) TextMessage(content)
      else parseOpt(content) map inferJsonMessageFromContent getOrElse TextMessage(content)
    }

    private def inferJsonMessageFromContent(content: JValue): WebSocketInMessage = {
      val contentType = (content \ "type").extractOpt[String].map(_.toLowerCase) getOrElse "none"
      (contentType) match {
        case "ack_request" => AckRequest(inferContentMessage(content \ "message"), (content \ "id").extract[Long])
        case "ack" => Ack((content \ "id").extract[Long])
        case "text" => TextMessage((content \ "content").extract[String])
        case "json" => JsonMessage((content \ "content"))
        case _ => JsonMessage(content)
      }
    }

    private def inferContentMessage(content: JValue): Ackable = {
      val contentType = (content \ "type").extractOrElse("none")
      (contentType) match {
        case "text" => TextMessage((content \ "content").extract[String])
        case "json" => JsonMessage((content \ "content"))
        case "none" => JsonMessage(content)
      }
    }

    override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
      host.receive.lift(Error(Option(e.getCause))) getOrElse logger.error("Oops, something went amiss", e.getCause)
      e.getChannel.close()
    }

    override def writeRequested(ctx: ChannelHandlerContext, e: MessageEvent) {
      e.getMessage match {
        case content: String => e.getChannel.write(new TextWebSocketFrame(content))
        case m: JsonMessage => sendOutMessage(m, e.getChannel)
        case m: TextMessage => sendOutMessage(m, e.getChannel)
        case BinaryMessage(content) => e.getChannel.write(new BinaryWebSocketFrame(ChannelBuffers.copiedBuffer(content)))
//        case NeedsAck(m, timeout) =>
        case Disconnect => // ignore here
        case _: WebSocketOutMessage =>
        case _ => ctx.sendDownstream(e)
      }
    }

    private def sendOutMessage(msg: WebSocketOutMessage with Ackable, channel: Channel) {
      msg match {
        case TextMessage(content) => channel.write(new TextWebSocketFrame(content))
        case JsonMessage(content) =>
          channel.write(new TextWebSocketFrame(compact(render(("type" -> "json") ~ ("content" -> content)))))
      }
    }

  }

  private final class WebSocketHost(val client: WebSocket)(implicit executionContext: ExecutionContext, formats: Formats) extends WebSocketLike with BroadcastChannel with Connectable {

    private[this] val normalized = client.uri.normalize()
    private[this] val tgt = if (normalized.getPath == null || normalized.getPath.trim().isEmpty) {
      new URI(normalized.getScheme, normalized.getAuthority,"/", normalized.getQuery, normalized.getFragment)
    } else normalized
    private[this] val protos = if (client.protocols.isEmpty) null else client.protocols.mkString(",")

    private[this] var bootstrap: ClientBootstrap = null
    private[this] val handshaker = new WebSocketClientHandshakerFactory().newHandshaker(tgt, client.version, protos, false, client.initialHeaders.asJava)
    private[this] val timer = new HashedWheelTimer()
    private[this] var channel: Channel = null
    private[this] var _isConnected: Promise[OperationResult] = Promise[OperationResult]()
    private[this] var _messageBuffer = new ConcurrentSkipListSet[WebSocketOutMessage]()

    def isConnected = channel != null && channel.isConnected && _isConnected.isCompleted



    private def configureBootstrap() {
      val self = this
      val ping = client.ping.duration.toSeconds.toInt
      bootstrap.setPipelineFactory(new ChannelPipelineFactory {
        def getPipeline = {
          val pipeline = Channels.pipeline()
          pipeline.addLast("timeouts", new IdleStateHandler(timer, ping, 0, 0))
          pipeline.addLast("pingpong", new PingPongHandler(logger))
          if (client.version == WebSocketVersion.V00)
            pipeline.addLast("decoder", new WebSocketHttpResponseDecoder)
          else
            pipeline.addLast("decoder", new HttpResponseDecoder)

          pipeline.addLast("encoder", new HttpRequestEncoder)
          pipeline.addLast("ws-handler", new WebSocketClientHostHandler(handshaker, self))
          pipeline.addLast("acking", new MessageAckingHandler(logger, client.raiseEvents))
          if (client.raiseEvents) {
          pipeline.addLast("eventsHook", new SimpleChannelHandler {
                  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
                    e.getMessage match {
                      case m: Ack => client.receive lift m
                      case m: AckRequest => client.receive lift m
                      case _ => ctx.sendUpstream(e)
                    }
                  }
                })
          }
          pipeline
        }
      })
    }

    def connect(): Future[OperationResult] = synchronized {
      bootstrap = new ClientBootstrap(new NioClientSocketChannelFactory(Executors.newCachedThreadPool, Executors.newCachedThreadPool))
      configureBootstrap()
      if (channel != null && channel.isConnected) Promise.successful(Success)
      else {
        val fut = bootstrap.connect(new InetSocketAddress(client.uri.getHost, client.uri.getPort))
        val af = fut.toAkkaFuture flatMap {
          case Success =>
            channel = fut.getChannel
            handshaker.handshake(channel).toAkkaFuture
          case x => Promise.successful(x)
        }
        try {
          Await.ready(af flatMap (_  =>_isConnected), 5 seconds)
        } catch {
          case ex => {
            bootstrap.releaseExternalResources()
            Promise.failed(ex)
          }
        }
      }
    }

    def close(): Future[OperationResult] = synchronized {
      channel.write(new CloseWebSocketFrame()).awaitUninterruptibly()
      val fut = if (channel == null) Promise.successful(Success) else channel.close().toAkkaFuture
      fut flatMap {
        case Success =>
          val kill = new Thread() {
            override def run = {
              if (bootstrap != null) bootstrap.releaseExternalResources()
            }
          }
          kill.setDaemon(false)
          kill.start()
          kill.join()
          _isConnected = Promise[OperationResult]()
          Promise.successful(Success)
        case x => Promise.successful(x)
      }
    }

    def id = if (channel == null) 0 else channel.id

    def send(message: WebSocketOutMessage): Future[OperationResult] = {
      connect() flatMap {
        case Success =>
          if (_isConnected.isCompleted) {
            channel.write(message).toAkkaFuture
          } else {
            logger debug "buffering message until fully connected"
            _messageBuffer add message
            Promise.successful(Success)
          }
        case x => Promise.successful(x)
      }
    }

    def drainBuffer() = {
      while(!_messageBuffer.isEmpty) {
        channel write _messageBuffer.pollFirst()
      }
    }

    def receive: Receive = internalReceive orElse client.receive

    private[this] def internalReceive: Receive = {
      case Connected =>
        _isConnected.success(Success)
        client.receive lift Connected
    }
  }

  /**
   * Fix bug in standard HttpResponseDecoder for web socket clients. When status 101 is received for Hybi00, there are 16
   * bytes of contents expected
   */
  class WebSocketHttpResponseDecoder extends HttpResponseDecoder {

    val codes = List(101, 200, 204, 205, 304)

    protected override def isContentAlwaysEmpty(msg: HttpMessage) = {
      msg match {
        case res: HttpResponse => codes contains res.getStatus.getCode
        case _ => false
      }
    }
  }

  implicit val executionContext =
    ExecutionContext.fromExecutorService(new ForkJoinPool(
      Runtime.getRuntime.availableProcessors(),
      ForkJoinPool.defaultForkJoinWorkerThreadFactory,
      new UncaughtExceptionHandler {
        def uncaughtException(t: Thread, e: Throwable) {
          e.printStackTrace()
        }
      },
      true))

  /**
   * A WebSocket related exception
   *
   * Copied from https://github.com/cgbystrom/netty-tools
   */
  class WebSocketException(s: String,  th: Throwable) extends java.io.IOException(s, th) {
    def this(s: String) = this(s, null)
  }

  class PingPongHandler(logger: InternalLogger) extends IdleStateAwareChannelHandler {

    override def channelIdle(ctx: ChannelHandlerContext, e: IdleStateEvent) {
      if (e.getState == IdleState.READER_IDLE || e.getState == IdleState.WRITER_IDLE) {
        logger.debug("Sending ping")
        e.getChannel.write(new PingWebSocketFrame())
      }
    }
  }


}

trait Connectable { self: BroadcastChannelLike =>
  def isConnected: Boolean
  def connect(): Future[OperationResult]
}

trait WebSocketLike extends BroadcastChannelLike {

  def receive: WebSocket.Receive
}

trait WebSocket extends WebSocketLike with Connectable {

  import WebSocket.WebSocketHost

  def uri: URI

  def protocols: Seq[String] = Nil

  def version: WebSocketVersion = WebSocketVersion.V13

  def initialHeaders: Map[String, String] = Map.empty[String, String]

  def ping = Timeout(60 seconds)

  private[websocket] def raiseEvents: Boolean = false

  implicit protected def executionContext = WebSocket.executionContext
  implicit protected def formats: Formats = DefaultFormats

  private[websocket] lazy val channel: BroadcastChannel with Connectable = new WebSocketHost(this)

  def isConnected = channel.isConnected

  final def connect(): Future[OperationResult] = channel.connect()

  final def close(): Future[OperationResult] = channel.close()

  final def send(message: WebSocketOutMessage): Future[OperationResult] = channel.send(message)

}
