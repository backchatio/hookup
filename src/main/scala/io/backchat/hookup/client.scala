package io.backchat.hookup

import http.Status
import org.jboss.netty.bootstrap.ClientBootstrap
import org.jboss.netty.channel._
import socket.nio.NioClientSocketChannelFactory
import org.jboss.netty.handler.codec.http._
import collection.JavaConverters._
import websocketx._
import org.jboss.netty.buffer.ChannelBuffers
import scala.concurrent.duration._
import scala.concurrent.{ Await, Promise, Future }
import org.jboss.netty.handler.timeout.{ IdleStateAwareChannelHandler, IdleStateEvent, IdleState, IdleStateHandler }
import org.jboss.netty.logging.{ InternalLogger, InternalLoggerFactory }
import io.backchat.hookup.HookupServer.MessageAckingHandler
import org.jboss.netty.util.{ Timeout ⇒ NettyTimeout, TimerTask, HashedWheelTimer, CharsetUtil }
import scala.concurrent.duration.Duration
import java.net.{ ConnectException, InetSocketAddress, URI }
import java.nio.channels.ClosedChannelException
import org.json4s._
import org.json4s.jackson.JsonMethods._
import java.io.Closeable
import java.util.concurrent.{ConcurrentSkipListSet, TimeUnit, Executors}
import beans.BeanProperty
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference, AtomicLong}
import akka.util.Timeout
import scala.concurrent.ExecutionContext

/**
 * @see [[io.backchat.hookup.HookupClient]]
 */
object HookupClient {

  /**
   * The websocket inbound message handler
   */
  type Receive = PartialFunction[InboundMessage, Unit]
  
  // TODO: put this her to get it compiling
  val executionContext = ExecutionContext.fromExecutor(ExecutionContext.global)

  /**
   * Global logger for a websocket client.
   */
  private val logger = InternalLoggerFactory.getInstance("HookupClient")

  /**
   * This handler takes care of translating websocket frames into [[io.backchat.hookup.InboundMessage]] instances
   * @param handshaker The handshaker to use for this websocket connection
   * @param host The host to connect to.
   */
  class WebSocketClientHostHandler(handshaker: WebSocketClientHandshaker, host: HookupClientHost) extends SimpleChannelHandler {
    private[this] val msgCount = new AtomicLong(0)
    private[this] def wireFormat = host.wireFormat.get
    private[this] val expectChunk = new AtomicBoolean(false)

    override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
      e.getMessage match {
        case resp: HttpResponse if handshaker.isHandshakeComplete ⇒
          throw new WebSocketException("Unexpected HttpResponse (status=" + resp.getStatus + ", content="
            + resp.getContent.toString(CharsetUtil.UTF_8) + ")")
        case resp: HttpResponse ⇒
          if (resp.headers.get(HttpHeaders.Names.SEC_WEBSOCKET_PROTOCOL) != null && resp.getStatus == Status.UpgradeRequired) {
            // TODO: add better handling of this so the people know what is going wrong
            logger.warn("The server only supports [%s] as protocols." format resp.headers.get(HttpHeaders.Names.SEC_WEBSOCKET_PROTOCOL))
            expectChunk.compareAndSet(false, true)
            host.disconnect()
          } else {
            handshaker.finishHandshake(ctx.getChannel, resp)
            // Netty doesn't implement the sub protocols for all handshakers,
            // otherwise handshaker.getActualSubProtocol would have been great
            resp.headers.get(HttpHeaders.Names.SEC_WEBSOCKET_PROTOCOL).blankOption foreach { p =>
              host.settings.protocols.find(_.name == p) foreach { wf =>
                host.wireFormat.set(wf)
                Channels.fireMessageReceived(ctx, SelectedWireFormat(wf))
              }
            }
            host.receive lift Connected
          }
        case resp: HttpChunk if expectChunk.compareAndSet(true, false) =>  // ignore the trailer for the handshake error
        case f: TextWebSocketFrame ⇒
          val inferred = wireFormat.parseInMessage(f.getText)
          inferred match {
            case a: Ack        ⇒ Channels.fireMessageReceived(ctx, a)
            case a: AckRequest ⇒ Channels.fireMessageReceived(ctx, a)
            case r             ⇒ host.receive lift r
          }
        case f: BinaryWebSocketFrame ⇒ host.receive lift BinaryMessage(f.getBinaryData.array())
        case f: ContinuationWebSocketFrame ⇒
          logger warn "Got a continuation frame this is not (yet) supported"
        case _: PingWebSocketFrame  ⇒ ctx.getChannel.write(new PongWebSocketFrame())
        case _: PongWebSocketFrame  ⇒
        case _: CloseWebSocketFrame ⇒ host.disconnect()
      }
    }

    override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
      (e.getCause) match {
        case _: ConnectException if host.isReconnecting  ⇒ // this is expected
        case _: ClosedChannelException if host.isClosing ⇒ // this is expected
        case ex ⇒
          host.receive.lift(Error(Option(e.getCause))) getOrElse logger.error("Oops, something went amiss", e.getCause)
          e.getChannel.close()
      }
    }

    override def writeRequested(ctx: ChannelHandlerContext, e: MessageEvent) {
      e.getMessage match {
        case content: String        ⇒ e.getChannel.write(new TextWebSocketFrame(content))
        case m: JsonMessage         ⇒ sendOutMessage(m, e.getChannel)
        case m: TextMessage         ⇒ sendOutMessage(m, e.getChannel)
        case BinaryMessage(content) ⇒ e.getChannel.write(new BinaryWebSocketFrame(ChannelBuffers.copiedBuffer(content)))
        case Disconnect             ⇒ // ignore here
        case _: OutboundMessage ⇒
        case _                      ⇒ ctx.sendDownstream(e)
      }
    }

    private def sendOutMessage(msg: OutboundMessage with Ackable, channel: Channel) {
      channel.write(new TextWebSocketFrame(wireFormat.render(msg)))
    }

    override def channelDisconnected(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
      if (!host.isClosing || host.isReconnecting) {
        host.reconnect()
      }
    }
  }

  /**
   * Implementation detail
   * the internal represenation of a websocket client.
   *
   * @param client The client to decorate
   * @param executionContext The execution context for futures
   */
  private final class HookupClientHost(val client: HookupClient)(implicit executionContext: ExecutionContext) extends HookupClientLike with BroadcastChannel with Connectable with Reconnectable {

    import HookupClientHost._

    private[this] val normalized = client.settings.uri.normalize()
    private[this] val tgt = if (normalized.getPath == null || normalized.getPath.trim().isEmpty) {
      new URI(normalized.getScheme, normalized.getAuthority, "/", normalized.getQuery, normalized.getFragment)
    } else normalized

    private[this] val bootstrap = new ClientBootstrap(clientSocketChannelFactory)
    private[this] var handshaker: WebSocketClientHandshaker = null
    private[this] var channel: Channel = null
    private[this] var _isConnected: Promise[OperationResult] = Promise[OperationResult]()
    private[this] val buffer = client.settings.buffer
    private[this] val memoryBuffer = new MemoryBuffer()
    val settings = client.settings
    val wireFormat = new AtomicReference[WireFormat](settings.defaultProtocol)

    def isConnected =
      channel != null && channel.isConnected && _isConnected.isCompleted && _isConnected.future.value.get == scala.util.Success(io.backchat.hookup.Success)
    //  && _isConnected.future.value.get == Right(Success)

    private def configureBootstrap() {
      val self = this
      val ping = client.settings.pinging.duration.toSeconds.toInt
      bootstrap.setPipelineFactory(new ChannelPipelineFactory {
        def getPipeline = {
          val pipeline = Channels.pipeline()
          pipeline.addLast("timeouts", new IdleStateHandler(timer, ping, 0, 0))
          pipeline.addLast("pingpong", new PingPongHandler(logger))
          if (client.settings.version == WebSocketVersion.V00)
            pipeline.addLast("decoder", new WebSocketHttpResponseDecoder)
          else
            pipeline.addLast("decoder", new HttpResponseDecoder)

          pipeline.addLast("encoder", new HttpRequestEncoder)
          pipeline.addLast("ws-handler", new WebSocketClientHostHandler(handshaker, self))
          pipeline.addLast("acking", new MessageAckingHandler(logger, settings.defaultProtocol, client.raiseEvents))
          if (client.raiseEvents) {
            pipeline.addLast("eventsHook", new SimpleChannelHandler {
              override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
                e.getMessage match {
                  case m: Ack        ⇒ client.receive lift m
                  case m: AckRequest ⇒ client.receive lift m
                  case _             ⇒ ctx.sendUpstream(e)
                }
              }
            })
          }
          pipeline
        }
      })
    }

    var throttle = client.settings.throttle
    var isClosing = false
    configureBootstrap()

    def connect(protocols: String*): Future[OperationResult] = synchronized {
      val protos = if (protocols.nonEmpty) protocols
      else Seq(settings.defaultProtocol.name)
      handshaker = new WebSocketClientHandshakerFactory().newHandshaker(tgt, client.settings.version, protos.mkString(","), false, client.settings.initialHeaders.asJava)
      isClosing = false
      val self = this
      if (isConnected) Promise.successful(Success).future
      else {
        val fut = bootstrap.connect(new InetSocketAddress(client.settings.uri.getHost, client.settings.uri.getPort))
        fut.addListener(new ChannelFutureListener {
          def operationComplete(future: ChannelFuture) {
             if (future.isSuccess && isReconnecting) {
              future.getChannel.getPipeline.replace("ws-handler", "ws-handler", new WebSocketClientHostHandler(handshaker, self))
            }
          }
        })
        val af = fut.toAkkaFuture.future flatMap {
          case Success ⇒
            throttle = client.settings.throttle
            channel = fut.getChannel
            handshaker.handshake(channel).toAkkaFuture.future
          case x ⇒ Promise.successful(x).future
        }

        try {

          val fut = af flatMap { _ ⇒
            isReconnecting = false

            _isConnected.future
          }

          buffer foreach (_.open())
          // TODO: not sure if this is doing what it's meant to do
          //val t = Await.ready(af, 5 seconds)
          val t = Await.result(af, 5 seconds)
          Promise.successful(t).future
        } catch {
          case ex: Throwable ⇒ {
            logger.error("Couldn't connect, killing all")
            reconnect()
          }
        }
      }
    }

    var isReconnecting = false

    def reconnect(): Future[OperationResult] = {
      if (!isReconnecting) client.receive lift Reconnecting
      isReconnecting = true
      disconnect() andThen {
        case _ ⇒
          delay {
            connect(wireFormat.get.name)
          }
      }
    }

    def delay(thunk: ⇒ Future[OperationResult]): Future[OperationResult] = {
      if (client.settings.throttle != NoThrottle) {
        val promise = Promise[OperationResult]()
        val theDelay = throttle.delay
        throttle = throttle.next()
        if (throttle != NoThrottle)
          timer.newTimeout(task(promise, theDelay, thunk), theDelay.toMillis, TimeUnit.MILLISECONDS)
        else promise.success(Cancelled)
        promise.future
      } else Promise.successful(Cancelled).future
    }

    private def task(promise: Promise[OperationResult], theDelay: Duration, thunk: ⇒ Future[OperationResult]): TimerTask = {
      import scala.util.{ Failure, Success }
      logger info "Connection to host [%s] lost, reconnecting in %s".format(client.settings.uri.toASCIIString, humanize(theDelay))
      new TimerTask {
        def run(timeout: NettyTimeout) {
          if (!timeout.isCancelled) {
            val rr = thunk
            rr onComplete {
              case Failure(_) => delay(thunk)
              case Success(x) => promise.success(x)
              //case Left(ex) ⇒ delay(thunk)
              //case Right(x) ⇒ promise.success(x)
            }
          } else promise.success(Cancelled)
        }
      }
    }

    private def humanize(dur: Duration) = {
      if (dur.toMillis < 1000)
        dur.toMillis.toString + " milliseconds"
      else if (dur.toSeconds < 60)
        dur.toSeconds.toString + " seconds"
      else if (dur.toMinutes < 60)
        dur.toMinutes.toString + " minutes"
    }

    def disconnect(): Future[OperationResult] = {
      isClosing = true
      val closing = Promise[OperationResult]()
      val disconnected = Promise[OperationResult]()

      if (channel != null) {
        if (isConnected) {
          channel.write(new CloseWebSocketFrame()).addListener(new ChannelFutureListener {
            def operationComplete(future: ChannelFuture) {
              future.getChannel.close().addListener(new ChannelFutureListener {
                def operationComplete(future: ChannelFuture) {
                  if (!isReconnecting) {
                    buffer foreach (_.close())
                    client.receive lift Disconnected(None)
                  }
                  _isConnected = Promise[OperationResult]()
                  disconnected.success(Success)
                }
              })
            }
          })
        } else {
          channel.close().addListener(new ChannelFutureListener {
            def operationComplete(future: ChannelFuture) {
              buffer foreach (_.close())
              client.receive lift Disconnected(None)
              _isConnected = Promise[OperationResult]()
              disconnected.success(Success)
            }
          })
        }
      } else {
        if (!isReconnecting) {
          buffer foreach (_.close())
          client.receive lift Disconnected(None)
        }
        disconnected.success(Success)
      }

      disconnected.future onComplete {
        _ ⇒  if (!closing.isCompleted) closing.success(Success)
      }

      closing.future
    }

    def id = if (channel == null) 0 else channel.id

    def send(message: OutboundMessage): Future[OperationResult] = {
      if (isConnected) {
        channel.write(message).toAkkaFuture.future
      } else {
        if (buffer.isDefined) {
          logger info "buffering message until fully connected"
          buffer foreach (_.write(message)(wireFormat.get))
        }
        if (buffer.isEmpty && !isReconnecting) // This is only for the first connect as it may take a bit longer than advertised
          memoryBuffer.write(message)(wireFormat.get)
        Promise.successful(Success).future
      }
    }

    def receive: Receive = internalReceive orElse client.receive

    def internalReceive: Receive = {
      case Connected ⇒ {
        memoryBuffer.drain(channel.send(_))(executionContext, wireFormat.get())
        buffer foreach { b ⇒
          b.drain(channel.send(_))(executionContext, wireFormat.get())
          _isConnected.success(Success)
          client.receive lift Connected
        }
        if (buffer.isEmpty) {
          _isConnected.success(Success)
          client.receive lift Connected
        }
      }
    }
  }

  object HookupClientHost {
    private lazy val clientSocketChannelFactory =
      new NioClientSocketChannelFactory(Executors.newCachedThreadPool(), Executors.newCachedThreadPool())

    private lazy val timer = new HashedWheelTimer
  }

  /**
   * Fix bug in standard HttpResponseDecoder for web socket clients. When status 101 is received for Hybi00, there are 16
   * bytes of contents expected
   */
  class WebSocketHttpResponseDecoder extends HttpResponseDecoder {

    val codes = List(101, 200, 204, 205, 304)

    protected override def isContentAlwaysEmpty(msg: HttpMessage) = {
      msg match {
        case res: HttpResponse ⇒ codes contains res.getStatus.getCode
        case _                 ⇒ false
      }
    }
  }

  import scala.concurrent.ExecutionContext.Implicits.global

  /**
   * The default execution context for the websocket library.
   * it uses a ForkJoinPool as underlying threadpool.
   */
  /* TODO: reimplement
  implicit lazy val executionContext =
    ExecutionContext.fromExecutorService(new ForkJoinPool(
      Runtime.getRuntime.availableProcessors()
      ForkJoinPool.defaultForkJoinWorkerThreadFactory)) */
      /*,
      new UncaughtExceptionHandler {
        def uncaughtException(t: Thread, e: Throwable) {
          e.printStackTrace()
        }
      },
      true)) */

  /**
   * A HookupClient related exception
   *
   * Copied from [[https://github.com/cgbystrom/netty-tools]]
   */
  class WebSocketException(s: String, th: Throwable) extends java.io.IOException(s, th) {
    def this(s: String) = this(s, null)
  }

  /**
   * Handler to send pings when no data has been sent or received within the timeout.
   * @param logger A logger for this handler to use.
   */
  class PingPongHandler(logger: InternalLogger) extends IdleStateAwareChannelHandler {

    override def channelIdle(ctx: ChannelHandlerContext, e: IdleStateEvent) {
      if (e.getState == IdleState.READER_IDLE || e.getState == IdleState.WRITER_IDLE) {
        if (e.getChannel.isConnected) e.getChannel.write(new PingWebSocketFrame())
      }
    }
  }

  /**
   * A factory method to create a default websocket implementation that uses the specified `recv` as message handler.
   * This probably not the most useful factory method ever unless you can keep the client around somehow too.
   *
   * @param context The configuration for the websocket client
   * @param recv The message handler
   * @return a [[io.backchat.hookup.DefaultHookupClient]]
   */
  def apply(context: HookupClientConfig)(recv: Receive): HookupClient = {
    new DefaultHookupClient(context) {
      val receive = recv
    }
  }


  /**
   * A factory method for the java api. It creates a JavaHookupClient which is a websocket with added helpers for
   * the java language, so they too can enjoy this library.
   *
   * @param context The configuration for the websocket client
   * @return a [[io.backchat.hookup.JavaHookupClient]]
   */
  def create(context: HookupClientConfig): JavaHookupClient =
    new JavaHookupClient(context)

}

/**
 * A trait describing a client that can connect to something
 */
trait Connectable { self: BroadcastChannelLike ⇒

  /**
   * A flag indicating connection status.
   * @return a boolean indicating connection status
   */
  @BeanProperty
  def isConnected: Boolean

  /**
   * Connect to the server
   * @return A [[scala.concurrent.Future]] with the [[io.backchat.hookup.OperationResult]]
   */
  def connect(protocols: String*): Future[OperationResult]
}

/**
 * A trait describing a client that can reconnect to a server.
 */
trait Reconnectable {

  /**
   * Reconnect to the server
   * @return A [[scala.concurrent.Future]] with the [[io.backchat.hookup.OperationResult]]
   */
  def reconnect(): Future[OperationResult]
}

/**
 * A trait describing an entity that can handle inbound messages
 */
trait HookupClientLike extends BroadcastChannelLike {

  /**
   * Handle inbound [[io.backchat.hookup.InboundMessage]] instances
   * @return a [[io.backchat.hookup.HookupClient.Receive]] as message handler
   */
  def receive: HookupClient.Receive
}

/**
 * The configuration of a websocket client.
 *
 * @param uri The [[java.net.URI]] to connect to.
 * @param version The version of the websocket handshake to use, defaults to the most recent version.
 * @param initialHeaders The headers to send along with the handshake request.
 * @param protocols The protocols this websocket client can understand
 * @param defaultProtocol the default protocol this client should use
 * @param pinging The timeout for pinging.
 * @param buffer The buffer to use when the connection to the server is lost.
 * @param throttle The throttle to use as reconnection schedule.
 * @param executionContext The execution context for futures.
 */
case class HookupClientConfig(
  @BeanProperty
  uri: URI,
  @BeanProperty
  version: WebSocketVersion = WebSocketVersion.V13,
  @BeanProperty
  initialHeaders: Map[String, String] = Map.empty,
  @BeanProperty
  protocols: Seq[WireFormat] = DefaultProtocols,
  @BeanProperty
  defaultProtocol: WireFormat = new SimpleJsonWireFormat()(DefaultFormats),
  @BeanProperty
  pinging: Timeout = Timeout(60 seconds),
  @BeanProperty
  buffer: Option[BackupBuffer] = None,
  @BeanProperty
  throttle: Throttle = NoThrottle,
  @BeanProperty
  executionContext: ExecutionContext = HookupClient.executionContext)

/**
 * The Hookup client provides a client for the hookup server, it doesn't lock you in to using a specific message format.
 * The default implementation uses websockets to communicate with the server.
 * You can opt-in or opt-out of every feature in the hookup client through configuration.
 *
 * Usage of the simple hookup client:
 *
 * <pre>
 *   new HookupClient {
 *     val uri = new URI("ws://localhost:8080/thesocket")
 *
 *     def receive = {
 *       case Disconnected(_) ⇒ println("The websocket to " + uri.toASCIIString + " disconnected.")
 *       case TextMessage(message) ⇒ {
 *         println("RECV: " + message)
 *         send("ECHO: " + message)
 *       }
 *     }
 *
 *     connect() onSuccess {
 *       case Success ⇒
 *         println("The websocket to " + uri.toASCIIString + "is connected.")
 *       case _ ⇒
 *     }
 *   }
 * </pre>
 *
 * @see [[io.backchat.hookup.HookupClientConfig]]
 */
trait HookupClient extends HookupClientLike with Connectable with Reconnectable with Closeable {

  import HookupClient.HookupClientHost

  /**
   * The configuration of this client.
   * @return The [[io.backchat.hookup.HookupClientConfig]] as configuration object
   */
  def settings: HookupClientConfig

  /**
   * A flag indicating whether this websocket client can fallback to buffering.
   * @return whether this websocket client can fallback to buffering or not.
   */
  def buffered: Boolean = settings.buffer.isDefined

  private[hookup] def raiseEvents: Boolean = false

  /**
   * The execution context for futures within this client.
   * @return The [[scala.concurrent.ExecutionContext]]
   */
  implicit protected lazy val executionContext: ExecutionContext = settings.executionContext

//  /**
//   * The json4s formats to use when serializing json values.
//   * @return The [[org.json4s.Formats]]
//   */
//  implicit protected def jsonFormats: Formats = DefaultFormats
//
//  /**
//   * The wireformat to use when sending messages over the connection.
//   * @return the [[io.backchat.hookup.WireFormat]]
//   */
//  implicit def wireFormat: WireFormat = new JsonProtocolWireFormat

  private[hookup] lazy val channel: BroadcastChannel with Connectable with Reconnectable = new HookupClientHost(this)

  def isConnected: Boolean = channel.isConnected

  /**
   * Send a message to the server.
   *
   * @param message The [[io.backchat.hookup.OutboundMessage]] to send
   * @return A [[scala.concurrent.Future]] with the [[io.backchat.hookup.OperationResult]]
   */
  final def !(message: OutboundMessage) = send(message)

  /**
   * Connect to the server.
   *
   * @return A [[scala.concurrent.Future]] with the [[io.backchat.hookup.OperationResult]]
   */
  final def connect(protocols: String*): Future[OperationResult] = channel.connect(protocols:_*)

  /**
   * Reconnect to the server.
   *
   * @return A [[scala.concurrent.Future]] with the [[io.backchat.hookup.OperationResult]]
   */
  def reconnect(): Future[OperationResult] = channel.reconnect()

  /**
   * Disconnect from the server.
   *
   * @return A [[scala.concurrent.Future]] with the [[io.backchat.hookup.OperationResult]]
   */
  final def disconnect(): Future[OperationResult] = channel.disconnect()


  final def close(): Unit = {
    Await.ready(disconnect(), 30 seconds)
  }

  /**
   * Send a message to the server.
   *
   * @param message The [[io.backchat.hookup.OutboundMessage]] to send
   * @return A [[scala.concurrent.Future]] with the [[io.backchat.hookup.OperationResult]]
   */
  final def send(message: OutboundMessage): Future[OperationResult] = channel.send(message)

  final def send(message: String): Future[OperationResult] = channel.send(TextMessage(message))

}

/**
 * Usage of the simple websocket client:
 *
 * <pre>
 *   new DefaultHookupClient(HookupClientConfig(new URI("ws://localhost:8080/thesocket"))) {
 *
 *     def receive = {
 *       case Disconnected(_) ⇒ println("The websocket to " + uri.toASCIIString + " disconnected.")
 *       case TextMessage(message) ⇒ {
 *         println("RECV: " + message)
 *         send("ECHO: " + message)
 *       }
 *     }
 *
 *     connect() onSuccess {
 *       case Success ⇒
 *         println("The websocket is connected.")
 *       case _ ⇒
 *     }
 *   }
 * </pre>
 */
abstract class DefaultHookupClient(val settings: HookupClientConfig) extends HookupClient

/**
 * A base class for the java api to listen for websocket events.
 */
trait WebSocketListener {
  /**
   * The callback method for when the client is connected
   *
   * @param client The client that connected.
   */
  def onConnected(client: HookupClient): Unit = ()

  /**
   * The callback method for when the client is reconnecting
   *
   * @param client The client that is reconnecting.
   */
  def onReconnecting(client: HookupClient): Unit = ()

  /**
   * The callback method for when the client is disconnected
   *
   * @param client The client that disconnected.
   */
  def onDisconnected(client: HookupClient, reason: Throwable): Unit = ()

  /**
   * The callback method for when a text message has been received.
   *
   * @param client The client that received the message
   * @param text The message it received
   */
  def onTextMessage(client: HookupClient, text: String): Unit = ()

  /**
   * The callback method for when a json message has been received.
   *
   * @param client The client that received the message
   * @param json The message it received
   */
  def onJsonMessage(client: HookupClient, json: String): Unit = ()

  /**
   * The callback method for when an error has occured
   *
   * @param client The client that received the message
   * @param reason The message it received the throwable if any, otherwise null
   */
  def onError(client: HookupClient, reason: Throwable): Unit = ()

  /**
   * The callback method for when a text message has failed to be acknowledged.
   *
   * @param client The client that received the message
   * @param text The message it received
   */
  def onTextAckFailed(client: HookupClient, text: String): Unit = ()

  /**
   * The callback method for when a json message has failed to be acknowledged.
   *
   * @param client The client that received the message
   * @param json The message it received
   */
  def onJsonAckFailed(client: HookupClient, json: String): Unit = ()
}

/**
 * A mixin for a [[io.backchat.hookup.HookupClient]] with helper methods for the java api.
 * When mixed into a websocket it is a full implementation that notifies the registered
 * [[io.backchat.hookup.WebSocketListener]] instances when events occur.
 */
trait JavaHelpers extends WebSocketListener { self: HookupClient =>

  /**
   * Send a text message. If the message is a json string it will still be turned into a json message
   *
   * @param message The message to send.
   * @return A [[scala.concurrent.Future]] with the [[io.backchat.hookup.OperationResult]]
   */
  // def send(message: String): Future[OperationResult] = channel.send(TextMessage(message))

  /**
   * Send a json message.
  json
   * @param json The message to send.
   * @return A [[scala.concurrent.Future]] with the [[io.backchat.hookup.OperationResult]]
   */
  def send(json: JValue): Future[OperationResult] = channel.send(json)

  /**
   * Send a json message. If the message isn't a json string it will throw a [[org.json4s.ParserUtil.ParseException]]
   *
   * @param json The message to send.
   * @return A [[scala.concurrent.Future]] with the [[io.backchat.hookup.OperationResult]]
   */
  def sendJson(json: String): Future[OperationResult] = channel.send(parse(json))

  /**
   * Send a text message which expects an Ack. If the message is a json string it will still be turned into a json message
   *
   * @param message The message to send.
   * @param timeout the [[scala.concurrent.duration.Duration]] as timeout for the ack operation
   * @return A [[scala.concurrent.Future]] with the [[io.backchat.hookup.OperationResult]]
   */
  def sendAcked(message: String, timeout: Duration): Future[OperationResult] = { 
    val msg = TextMessage(message)
    channel.send(msg.needsAck(timeout))
  }
    // channel.send(message.needsAck(timeout))
  /**
   * Send a json message which expects an Ack. If the message isn't a json string it will throw a [[org.json4s.ParserUtil.ParseException]]
   *
   * @param message The message to send.
   * @param timeout the [[scala.concurrent.duration.Duration]] as timeout for the ack operation
   * @return A [[scala.concurrent.Future]] with the [[io.backchat.hookup.OperationResult]]
   */
  def sendAcked(message: JValue, timeout: Duration): Future[OperationResult] = channel.send(message.needsAck(timeout))

  /**
   * Send a text message which expects an Ack. If the message is a json string it will still be turned into a json message
   *
   * @param json The message to send.
   * @param timeout the [[scala.concurrent.duration.Duration]] as timeout for the ack operation
   * @return A [[scala.concurrent.Future]] with the [[io.backchat.hookup.OperationResult]]
   */
  def sendJsonAcked(json: String, timeout: Duration): Future[OperationResult] = channel.send(parse(json).needsAck(timeout))

  private[this] val listeners = new ConcurrentSkipListSet[WebSocketListener]()

  /**
   * Add a listener for websocket events, if you want to remove the listener at a later time you need to keep the instance around.
   * @param listener The [[io.backchat.hookup.WebSocketListener]] to add
   * @return this to allow for chaining
   */
  def addListener(listener: WebSocketListener): this.type = {
    listeners.add(listener)
    this
  }

  /**
   * Remove a listener for websocket events
   * @param listener The [[io.backchat.hookup.WebSocketListener]] to add
   * @return this to allow for chaining
   */
  def removeListener(listener: WebSocketListener): this.type = {
    listeners.remove(listener)
    this
  }

  /**
   * The implementation of the receive handler for java clients.
   * it notfies the listeners by iterating over all of them and calling the designated method.
   * @return The [[io.backchat.hookup.HookupClient.Receive]] message handler
   */
  def receive: HookupClient.Receive = {
    case Connected =>
      listeners.asScala foreach (_.onConnected(this))
      onConnected(this)
    case Disconnected(reason) =>
      listeners.asScala foreach (_.onDisconnected(this, reason.orNull))
      onDisconnected(this, reason.orNull)
    case Reconnecting =>
      listeners.asScala foreach (_.onReconnecting(this))
      onReconnecting(this)
    case TextMessage(text) =>
      listeners.asScala foreach (_.onTextMessage(this, text))
      onTextMessage(this, text)
    case JsonMessage(text) =>
      val str = compact(render(text))
      listeners.asScala foreach (_.onJsonMessage(this, str))
      onJsonMessage(this, str)
    case AckFailed(TextMessage(text)) =>
      listeners.asScala foreach (_.onTextAckFailed(this, text))
      onTextAckFailed(this, text)
    case AckFailed(JsonMessage(json)) =>
      val str = compact(render(json))
      listeners.asScala foreach (_.onJsonAckFailed(this, str))
      onJsonAckFailed(this, str)
    case Error(reason) =>
      listeners.asScala foreach (_.onError(this, reason.orNull))
      onError(this, reason.orNull)
  }
}

/**
 * A java friendly websocket
 * @see [[io.backchat.hookup.HookupClient]]
 * @see [[io.backchat.hookup.JavaHelpers]]
 *
 * @param settings The settings to use when creating this websocket.
 */
class JavaHookupClient(settings: HookupClientConfig) extends DefaultHookupClient(settings) with JavaHelpers

//trait BufferedWebSocket { self: HookupClient ⇒
//
//  override def throttle = IndefiniteThrottle(500 millis, 30 minutes)
//
//  override def bufferPath = new File("./work/buffer.log")
//  override def buffered = true
//
//}
