package io.backchat.websocket

import org.jboss.netty.bootstrap.ServerBootstrap
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory
import org.jboss.netty.channel._
import group.{ChannelGroup, DefaultChannelGroup}
import org.jboss.netty.handler.codec.http.websocketx._
import org.jboss.netty.handler.codec.http.HttpHeaders.Values
import org.jboss.netty.handler.codec.http.HttpHeaders.Names
import java.util.Locale.ENGLISH
import org.jboss.netty.logging.{InternalLogger, InternalLoggerFactory}
import java.security.KeyStore
import java.io.{FileInputStream, File}
import javax.net.ssl.{KeyManagerFactory, SSLContext}
import org.jboss.netty.handler.ssl.SslHandler
import org.jboss.netty.handler.codec.http._
import java.net.{SocketAddress, InetSocketAddress}
import scala.collection.JavaConverters._
import collection.mutable.ListBuffer
import akka.dispatch.{Promise, Future}
import akka.util.duration._
import org.jboss.netty.handler.timeout.{IdleStateEvent, IdleState, IdleStateHandler, IdleStateAwareChannelHandler}
import java.util.concurrent.atomic.AtomicLong
import net.liftweb.json._
import JsonDSL._
import java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedQueue, TimeUnit, Executors}
import akka.actor.{DefaultCancellable, Cancellable}
import io.backchat.websocket.WebSocketServer.{WebSocketCancellable}
import akka.util.{Index, Timeout}
import annotation.switch
import org.jboss.netty.handler.codec.frame.FrameDecoder
import org.jboss.netty.buffer.{ChannelBuffer, ChannelBuffers}
import org.jboss.netty.util.{CharsetUtil, Timeout => NettyTimeout, TimerTask, HashedWheelTimer}

trait ServerCapability

/**
 * Configuration for adding ssl support to the server
 *
 * @param keystorePath path to the keystore, by default it looks for the system property keystore.file.path
 * @param keystorePassword path to the keystore, by default it looks for the system property keystore.file.path
 * @param algorithm path to the keystore, by default it looks for the system property keystore.file.path
 */
case class SslSupport(
             keystorePath: String = sys.props("keystore.file.path"),
             keystorePassword: String = sys.props("keystore.file.password"),
             algorithm: String = sys.props.get("ssl.KeyManagerFactory.algorithm").flatMap(_.blankOption) | "SunX509") extends ServerCapability

case class ContentCompression(level: Int = 6) extends ServerCapability
case class SubProtocols(protocol: String, protocols: String*) extends ServerCapability
case class Ping(timeout: Timeout) extends ServerCapability
case class FlashPolicy(domain: String, port: Seq[Int]) extends ServerCapability
private[websocket] case object RaiseAckEvents extends ServerCapability
private[websocket] case object RaisePingEvents extends ServerCapability

/**
 * Main configuration object for a server
 *
 * @param name The name of this server, defaults to BackchatWebSocketServer
 * @param version The version of the server
 * @param listenOn Which address the server should listen on
 * @param port The port the server should listen on
 * @param capabilities A varargs of extra capabilities of this server
 */
case class ServerInfo(
              name: String,
              version: String = Version.version,
              listenOn: String = "0.0.0.0",
              port: Int = 8765,
              capabilities: Seq[ServerCapability] = Seq.empty) {

  val sslContext = (capabilities collect {
    case cfg: SslSupport => {
      val ks = KeyStore.getInstance("JKS")
      val fin = new FileInputStream(new File(cfg.keystorePath).getAbsoluteFile)
      ks.load(fin, cfg.keystorePassword.toCharArray)
      val kmf = KeyManagerFactory.getInstance(cfg.algorithm)
      kmf.init(ks, cfg.keystorePassword.toCharArray)
      val context = SSLContext.getInstance("TLS")
      context.init(kmf.getKeyManagers, null, null)
      context
    }
  }).headOption

  val contentCompression = (capabilities collect { case c: ContentCompression => c }).headOption

  val subProtocols = (capabilities collect {
    case sp: SubProtocols if sp.protocols.nonEmpty => sp.protocol :+ sp.protocols
  }).headOption

  val pingTimeout = (capabilities collect {
    case Ping(timeout) => timeout
  }).headOption | Timeout(90 seconds)

  val flashPolicy = (capabilities collect {
    case FlashPolicy(domain, policyPorts) =>
      (<cross-domain-policy>
         <allow-access-from domain={ domain } to-ports={ policyPorts.mkString(",") }/>
       </cross-domain-policy>).toString()
  }).headOption getOrElse {
    (<cross-domain-policy>
       <allow-access-from domain="*" to-ports="*" />
     </cross-domain-policy>).toString()
  }
}


/**
* Netty based WebSocketServer
* requires netty 3.3.x or later
*
* Usage:
* <pre>
*   val server = WebSocketServer(ServerInfo("MyWebSocketServer")) {
*     new WebSocketServerClient {
*       protected val receive = {
*         case Connected => println("got a client connection")
*         case TextMessage(text) => send(TextMessage("ECHO: " + text))
*         case Disconnected(_) => println("client disconnected")
*       }
*     }
*   }
*   server.start
*   // time passes......
*   server.stop
* </pre>
*/
object WebSocketServer {

//  type WebSocketHandler = PartialFunction[WebSocketMessage, Unit]


  import WebSocket.{ executionContext, Receive }

  trait BroadcastFilter extends (BroadcastChannel => Boolean) with NotNull

  object Include {
    def apply(clients: WebSocketServerClient*): BroadcastFilter = new Include(clients)
  }
  class Include(clients: Seq[WebSocketServerClient]) extends BroadcastFilter {
    def apply(channel: BroadcastChannel) = clients.exists(_.id == channel.id)
  }

  object Exclude {
    def apply(clients: WebSocketServerClient*): BroadcastFilter = new Exclude(clients)
  }

  class Exclude(clients: Seq[WebSocketServerClient]) extends BroadcastFilter {
    def apply(v1: BroadcastChannel) = !clients.exists(_.id == v1.id)
  }


//  private implicit def wsServerClient2BroadcastChannel(ch: WebSocketServerClientHandler): BroadcastChannel =
//    new { val id = ch.id } with BroadcastChannel { def send(msg: String) { ch.send(msg) } }
//

  trait Broadcast {
    def apply(message: WebSocketOutMessage, allowsOnly: BroadcastFilter): Future[OperationResult]
  }



  private implicit def nettyChannelGroup2Broadcaster(allChannels: ChannelGroup): Broadcast = new Broadcast {
    def apply(message: WebSocketOutMessage, matchingOnly: BroadcastFilter) = {
      val lst = allChannels.asScala map (x => x: BroadcastChannel) filter matchingOnly map (_ send message)
      Future.sequence(lst) map (l => ResultList(l.toList))
    }
  }

  trait WebSocketServerClient extends BroadcastChannel {

    val SkipSelf = Exclude(this)
    final def id = if (_handler != null) _handler.id else 0
    final def remoteAddress = if (_handler != null) _handler.remoteAddress else null

    private[WebSocketServer] var _handler: WebSocketServerClientHandler = null
    private val _buffer = new ConcurrentLinkedQueue[WebSocketOutMessage]()
    private val _broadcastBuffer = new ConcurrentLinkedQueue[(WebSocketOutMessage, BroadcastFilter)]()

    /**
     * Send a text message to this client
     * @param message the message to send
     */
    final def send(message: WebSocketOutMessage): Future[OperationResult] = {
      if (_handler != null) {
        val futures = new ListBuffer[Future[OperationResult]]
        while(!_buffer.isEmpty) {
          futures += _handler.send(_buffer.poll())
        }
        futures += _handler send message
        Future.sequence(futures).map(r => ResultList(r.toList))
      } else {
        _buffer offer message
        Promise.successful(Success)
      }
    }

    /**
     * alias for [[send]] @see send
     */
    final def !(msg: WebSocketOutMessage) { send(msg) }

    final def broadcast(msg: WebSocketOutMessage, onlyTo: BroadcastFilter = SkipSelf): Future[OperationResult] = {
      if (_handler != null) {
        val futures = new ListBuffer[Future[OperationResult]]
        while(!_broadcastBuffer.isEmpty) {
          futures += (((_handler.broadcast _) tupled) apply _broadcastBuffer.poll())
        }
        futures += _handler.broadcast(msg, onlyTo)
        Future.sequence(futures.toList) map ResultList.apply
      } else {
        _broadcastBuffer.offer((msg, onlyTo))
        Promise.successful(Success)
      }
    }
    final def ><(msg: WebSocketOutMessage, onlyTo: BroadcastFilter = SkipSelf): Future[OperationResult] = broadcast(msg, onlyTo)

    def receive: Receive

    final def close() = {
      if (_handler != null) _handler.close()
      else Promise.successful(Success)
    }

  }

  /**
   * Represents a client connection to this server
   */
  private abstract class WebSocketServerClientHandler(channel: BroadcastChannel, client: WebSocketServerClient, logger: InternalLogger, broadcaster: Broadcast)  {

    client._handler = this

    /**
     * The id of this connection
     */
    val id: Int = channel.id

    /**
     * The address this client is connecting from
     */
    val remoteAddress: SocketAddress

    /**
     * Send a text message to this client
     * @param message the message to send
     */
    final def send(message: WebSocketOutMessage) = channel.send(message)

    /**
     * alias for [[send]] @see send
     */
    def !(msg: WebSocketOutMessage) { send(msg) }

    final def broadcast(msg: WebSocketOutMessage, matchingOnly: BroadcastFilter) = broadcaster(msg, matchingOnly)

    final def ><(msg: WebSocketOutMessage, matchingOnly: BroadcastFilter) { broadcaster(msg, matchingOnly) }

    def receive: Receive = client.receive orElse defaultReceive

    private val defaultReceive: Receive = {
      case Error(Some(ex)) =>
        logger.error("Received an error.", ex)
      case Error(_) =>
        logger.error("Unknown error occurred")
      case _ =>
    }

    def close() = {
      channel.send(Disconnect)
    };
  }

  val DefaultServerName = "BackChatWebSocketServer"

  def apply(capabilities: ServerCapability*)(factory: => WebSocketServerClient)(implicit formats: Formats): WebSocketServer = {
    apply(ServerInfo(DefaultServerName, capabilities = capabilities))(factory)
  }

  def apply(port: Int,  capabilities: ServerCapability*)(factory: => WebSocketServerClient)(implicit formats: Formats): WebSocketServer = {
    apply(ServerInfo(DefaultServerName, port = port, capabilities = capabilities))(factory)
  }

  def apply(listenOn: String,  capabilities: ServerCapability*)(factory: => WebSocketServerClient)(implicit formats: Formats): WebSocketServer = {
    apply(ServerInfo(DefaultServerName, listenOn = listenOn, capabilities = capabilities))(factory)
  }

  def apply(listenOn: String, port: Int,  capabilities: ServerCapability*)(factory: => WebSocketServerClient)(implicit formats: Formats): WebSocketServer = {
    apply(ServerInfo(DefaultServerName, listenOn = listenOn, port = port, capabilities = capabilities))(factory)
  }

  def apply(name: String, listenOn: String, port: Int,  capabilities: ServerCapability*)(factory: => WebSocketServerClient)(implicit formats: Formats): WebSocketServer = {
    apply(ServerInfo(DefaultServerName, listenOn = listenOn, port = port, capabilities = capabilities))(factory)
  }

  def apply(info: ServerInfo)(factory: => WebSocketServerClient)(implicit formats: Formats): WebSocketServer = {
    new WebSocketServer(info, factory)
  }

  private class ConnectionTracker(channels: ChannelGroup) extends SimpleChannelUpstreamHandler {
    override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
      channels remove e.getChannel
      ctx.sendUpstream(e)
    }

    override def channelConnected(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
      channels add e.getChannel
      ctx.sendUpstream(e)
    }

    override def channelDisconnected(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
      channels remove e.getChannel
      ctx.sendUpstream(e)
    }

  }

  private[this] val PolicyXml = <cross-domain-policy><allow-access-from domain="*" to-ports="*" /></cross-domain-policy>
  private val AllowAllPolicy = ChannelBuffers.copiedBuffer(PolicyXml.toString(), CharsetUtil.UTF_8)


  class FlashPolicyHandler(policyResponse: ChannelBuffer = AllowAllPolicy) extends FrameDecoder {


    def decode(ctx: ChannelHandlerContext, channel: Channel, buffer: ChannelBuffer) = {
      if (buffer.readableBytes > 1) {

        val magic1 = buffer.getUnsignedByte(buffer.readerIndex());
        val magic2 = buffer.getUnsignedByte(buffer.readerIndex() + 1);
        val isFlashPolicyRequest = (magic1 == '<' && magic2 == 'p');

        if (isFlashPolicyRequest) {
          // Discard everything
          buffer.skipBytes(buffer.readableBytes())

          // Make sure we don't have any downstream handlers interfering with our injected write of policy request.
          removeAllPipelineHandlers(channel.getPipeline)
          channel.write(policyResponse).addListener(ChannelFutureListener.CLOSE)
          null
        } else {

          // Remove ourselves, important since the byte length check at top can hinder frame decoding
          // down the pipeline
          ctx.getPipeline.remove(this)
          buffer.readBytes(buffer.readableBytes())
        }
      } else null
    }

    private def removeAllPipelineHandlers(pipe: ChannelPipeline) {
      while (pipe.getFirst != null) {
        pipe.removeFirst();
      }
    }
  }

  private[this] object OneHundredContinueResponse extends DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE)
  private final class WebSocketClientFactoryHandler(logger: InternalLogger,
                                                    allChannels: ChannelGroup,
                                                    factory: => WebSocketServerClient,
                                                    subProtocols: Traversable[String] = Nil,
                                                    raiseEvents: Boolean = false)(implicit formats: Formats) extends SimpleChannelUpstreamHandler {

    private[this] var collectedFrames: Seq[ContinuationWebSocketFrame] = Vector.empty[ContinuationWebSocketFrame]

    private[this] var handshaker: WebSocketServerHandshaker = _

    private[this] var client: WebSocketServerClientHandler = null

    private[this] var receivedCloseFrame: Boolean = false

    private[this] def clientFrom(ctx: ChannelHandlerContext): WebSocketServerClientHandler = {
      (Option(ctx.getAttachment) collect {
        case h: WebSocketServerClientHandler => h
      }) getOrElse {
        val sockAddr = ctx.getChannel.getRemoteAddress
        val h = new WebSocketServerClientHandler(ctx.getChannel, factory, logger, allChannels) {
          val remoteAddress = sockAddr
        }
        ctx.setAttachment(h)
        h
      }
    }

    override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
      e.getMessage match {
        case request: HttpRequest if HttpHeaders.is100ContinueExpected(request) =>
          Channels.write(ctx, Channels.future(ctx.getChannel), OneHundredContinueResponse, e.getRemoteAddress)
          request.removeHeader(HttpHeaders.Names.EXPECT)
          ctx.sendUpstream(e)

        case httpRequest: HttpRequest if isWebSocketUpgrade(httpRequest) ⇒ handleUpgrade(ctx, httpRequest)

        case m: TextWebSocketFrame => {
          WebSocket.ParseToWebSocketInMessage(m.getText) match {
            case a: Ack => Channels.fireMessageReceived(ctx, a)
            case a: AckRequest => Channels.fireMessageReceived(ctx, a)
            case r => client.receive lift r
          }
        }

        case m: BinaryWebSocketFrame => client.receive lift BinaryMessage(m.getBinaryData.array)

        case m: ContinuationWebSocketFrame =>
          if (m.isFinalFragment) {
            client.receive lift TextMessage(collectedFrames map (_.getText) reduce (_ + _))
            collectedFrames = Nil
          } else {
            collectedFrames :+= m
          }

        case f: WebSocketInMessage => client.receive lift f
        case f: CloseWebSocketFrame ⇒
          receivedCloseFrame = true
          if (handshaker != null) handshaker.close(ctx.getChannel, f)

        case _: PingWebSocketFrame ⇒ e.getChannel.write(new PongWebSocketFrame)

        case _ ⇒ ctx.sendUpstream(e)
      }
    }

    override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
      if (client.receive != null) client.receive lift Error(Option(e.getCause))
      else logger.error("Exception during connection.", e.getCause)
    }


    override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
      client.receive lift Disconnected(None)
      client = null
      ctx.setAttachment(null)
    }

    private def isWebSocketUpgrade(httpRequest: HttpRequest): Boolean = {
      val connHdr = httpRequest.getHeader(Names.CONNECTION)
      val upgrHdr = httpRequest.getHeader(Names.UPGRADE)
      (connHdr != null && connHdr.equalsIgnoreCase(Values.UPGRADE)) &&
        (upgrHdr != null && upgrHdr.equalsIgnoreCase(Values.WEBSOCKET))
    }

    private def handleUpgrade(ctx: ChannelHandlerContext, httpRequest: HttpRequest) {
      val protos = if (subProtocols.isEmpty) null else subProtocols.mkString(",")
      val handshakerFactory = new WebSocketServerHandshakerFactory(websocketLocation(httpRequest), protos, false)
      handshaker = handshakerFactory.newHandshaker(httpRequest)
      if (handshaker == null) handshakerFactory.sendUnsupportedWebSocketVersionResponse(ctx.getChannel)
      else {
        handshaker.handshake(ctx.getChannel, httpRequest)
        client = clientFrom(ctx)
        if (raiseEvents) Channels.fireMessageReceived(ctx, ("client" -> client))
        client.receive.lift(Connected)
      }
    }

    private def isHttps(req: HttpRequest) = {
      val h1 = Option(req.getHeader("REQUEST_URI")).filter(_.trim.nonEmpty)
      val h2 = Option(req.getHeader("X-Forwarded-Proto")).filter(_.trim.nonEmpty)
      (h1.isDefined && h1.forall(_.toUpperCase(ENGLISH).startsWith("HTTPS"))) ||
        (h2.isDefined && h2.forall(_.toUpperCase(ENGLISH) startsWith "HTTPS"))
    }

    private def websocketLocation(req: HttpRequest) = {
      if (isHttps(req))
        "wss://" + req.getHeader(Names.HOST) + "/"
      else
        "ws://" + req.getHeader(Names.HOST) + "/"
    }
  }

  class WebSocketMessageAdapter(logger: InternalLogger)(implicit formats: Formats) extends SimpleChannelDownstreamHandler {
    override def writeRequested(ctx: ChannelHandlerContext, e: MessageEvent) {
      e.getMessage match {
        case m: JsonMessage => writeOutMessage(ctx, m)
        case m: TextMessage => writeOutMessage(ctx, m)
        case Disconnect => ctx.getChannel.write(new CloseWebSocketFrame()).addListener(ChannelFutureListener.CLOSE)
        case BinaryMessage(bytes) => {
          ctx.getChannel.write(new BinaryWebSocketFrame(ChannelBuffers.copiedBuffer(bytes)))
        }
        case _ => {
          ctx.sendDownstream(e)
        }
      }
    }

    private def writeOutMessage(ctx: ChannelHandlerContext, msg: WebSocketOutMessage) {
      ctx.getChannel.write(new TextWebSocketFrame(WebSocket.RenderOutMessage(msg)))
    }
  }

  private class WebSocketCancellable(timeout: NettyTimeout) extends Cancellable {
    def cancel() {
      timeout.cancel()
    }

    def isCancelled = timeout.isCancelled


  }

  class MessageAckingHandler(logger: InternalLogger, raiseEvents: Boolean = false)(implicit formats: Formats) extends SimpleChannelHandler {

    private[this] val messageCounter = new AtomicLong
    private[this] val expectedAcks = new ConcurrentHashMap[Long, Cancellable]()
    private[this] val ackScavenger = new HashedWheelTimer()

    override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
      e.getMessage match {
        case Ack(id) =>
          val ack = expectedAcks.remove(id)
          if (ack != null) ack.cancel()
          if (raiseEvents) ctx.sendUpstream(e)
        case AckRequest(msg, id) => {
          ctx.getChannel.write(Ack(id))
          if (raiseEvents) Channels.fireMessageReceived(ctx, AckRequest(msg, id))
          Channels.fireMessageReceived(ctx, msg)
        }
         case _ => ctx.sendUpstream(e)
      }
    }

    override def writeRequested(ctx: ChannelHandlerContext, e: MessageEvent) {
      e.getMessage match {
        case m: Ack =>
          ctx.getChannel.write(new TextWebSocketFrame(WebSocket.RenderOutMessage(m)))
        case NeedsAck(m, timeout) =>
          val id = createAck(ctx, m, timeout)
          if (raiseEvents) Channels.fireMessageReceived(ctx, AckRequest(m, id))
        case _ => ctx.sendDownstream(e)
      }
    }

    private[this] def createAck(ctx: ChannelHandlerContext, message: Ackable, timeout: Timeout) = {
      val id = messageCounter.incrementAndGet()
      val (ct, data) = contentFrom(message)
      val msg: JValue =
        ("message" -> (
          ("type" -> ct) ~
          ("content" -> data))) ~
        ("type" -> "ack_request") ~
        ("id" -> id)

      val to: NettyTimeout = ackScavenger.newTimeout(new TimerTask {
        def run(timeout: NettyTimeout) {
          if (!timeout.isCancelled) Channels.fireMessageReceived(ctx, AckFailed(message.asInstanceOf[WebSocketOutMessage]))
        }
      }, timeout.duration.toMillis, TimeUnit.MILLISECONDS)
      val exp = new WebSocketCancellable(to)
      while(expectedAcks.put(id, exp) != null) { // spin until we've updated

      }
      ctx.getChannel.write(new TextWebSocketFrame(compact(render(msg))))
      id
    }



    private[this] def contentFrom(message: Ackable): (String, JValue) = message match {
      case TextMessage(text) => ("text", JString(text))
      case JsonMessage(json) => ("json", json)
    }
  }

}

class WebSocketServer(val config: ServerInfo, factory: => WebSocketServerClient)(implicit formats: Formats = DefaultFormats) {
  def capabilities = config.capabilities
  def name = config.name
  def version = config.version
  def listenOn = config.listenOn
  def port = config.port


  import WebSocketServer._
  protected val logger = InternalLoggerFactory.getInstance(name)
  private[this] val timer = new HashedWheelTimer()
  private[this] var server: ServerBootstrap = null

  private[this] val allChannels = new DefaultChannelGroup

  /**
   * If you want to override the entire Netty Channel Pipeline that gets created override this method.
   *
   * @return the created [[org.jboss.netty.channel.ChannelPipeline]]
   */
  protected def getPipeline: ChannelPipeline = {
    val pipe = Channels.pipeline()
    pipe.addLast("flash-policy", new FlashPolicyHandler(ChannelBuffers.copiedBuffer(config.flashPolicy, CharsetUtil.UTF_8)))
    pipe.addLast("connection-tracker", new ConnectionTracker(allChannels))
    addFirstInPipeline(pipe)
    config.sslContext foreach { ctxt =>
      val engine = ctxt.createSSLEngine()
      engine.setUseClientMode(false)
      pipe.addLast("ssl", new SslHandler(engine))
    }
    val ping = config.pingTimeout.duration.toSeconds.toInt
    if (ping > 0) {
      pipe.addLast("timeouts", new IdleStateHandler(timer, 0, ping, 0))
      pipe.addLast("connection-reaper", new WebSocket.PingPongHandler(logger))
    }
    pipe.addLast("decoder", new HttpRequestDecoder(4096, 8192, 8192))
    pipe.addLast("aggregator", new HttpChunkAggregator(64 * 1024))
    pipe.addLast("encoder", new HttpResponseEncoder)
    config.contentCompression foreach { ctx =>
      pipe.addLast("deflater", new HttpContentCompressor(ctx.level))
    }
    val raiseEvents = capabilities.contains(RaiseAckEvents)
    configurePipeline(pipe)
    pipe.addLast("websockethandler", new WebSocketClientFactoryHandler(logger, allChannels, factory, raiseEvents = raiseEvents))
    pipe.addLast("websocketoutput", new WebSocketMessageAdapter(logger))
    pipe.addLast("acking", new MessageAckingHandler(logger, capabilities.contains(RaiseAckEvents)))
    if (raiseEvents) {
      pipe.addLast("eventsHook", new SimpleChannelHandler {
        var theclient: WebSocketServerClientHandler = null
        override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
          e.getMessage match {
            case ("client", c: WebSocketServerClientHandler) => theclient = c
            case m: Ack => theclient.receive lift m
            case m: AckRequest => theclient.receive lift m
            case _ => ctx.sendUpstream(e)
          }
        }
      })
    }
    addLastInPipeline(pipe)
    pipe
  }

  protected def addFirstInPipeline(pipe: ChannelPipeline) {

  }

  protected def configurePipeline(pipe: ChannelPipeline) {

  }

  protected def addLastInPipeline(pipe: ChannelPipeline) {

  }

  private[this] def pipelineFactory = new ChannelPipelineFactory {
    def getPipeline = WebSocketServer.this.getPipeline
  }

  private[this] val startCallbacks = new ListBuffer[() => Any]()
  private[this] val stopCallbacks = new ListBuffer[() => Any]()

  def onStart(thunk: => Any) = startCallbacks += { () => thunk }
  def onStop(thunk: => Any) = stopCallbacks += { () => thunk }

  final def start = synchronized {
    server = new ServerBootstrap(new NioServerSocketChannelFactory(Executors.newCachedThreadPool(), Executors.newCachedThreadPool()))
    server.setOption("soLinger", 0)
    server.setOption("reuseAddress", true)
    server.setOption("child.tcpNoDelay", true)
    server.setPipelineFactory(pipelineFactory)
    val addr = config.listenOn.blankOption.map(l =>new InetSocketAddress(l, config.port) ) | new InetSocketAddress(config.port)
    val sc = server.bind(addr)
    allChannels add sc
    sys.addShutdownHook(stop)
    startCallbacks foreach (_.apply())
    logger info "Started %s on [%s:%d]".format(config.name, config.listenOn, config.port)
  }

  final def stop = synchronized {
    stopCallbacks foreach (_.apply())
    allChannels.close().awaitUninterruptibly()
    val thread = new Thread {
      override def run = {
        if (server != null) {
          server.releaseExternalResources()
        }
      }
    }
    thread.setDaemon(false)
    thread.start()
    thread.join()
    logger info "Stopped %s".format(config.name)
  }
}