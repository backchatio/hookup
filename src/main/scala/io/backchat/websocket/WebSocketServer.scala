package io.backchat.websocket

import org.jboss.netty.bootstrap.ServerBootstrap
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory
import org.jboss.netty.channel._
import group.{ChannelGroup, DefaultChannelGroup}
import org.jboss.netty.handler.codec.http.websocketx._
import org.jboss.netty.handler.codec.http.HttpHeaders.Values
import org.jboss.netty.handler.codec.http.HttpHeaders.Names
import java.util.Locale.ENGLISH
import org.jboss.netty.buffer.ChannelBuffers
import org.jboss.netty.logging.{InternalLogger, InternalLoggerFactory}
import java.security.KeyStore
import java.io.{FileInputStream, File}
import javax.net.ssl.{KeyManagerFactory, SSLContext}
import org.jboss.netty.handler.ssl.SslHandler
import org.jboss.netty.handler.codec.http._
import java.net.{SocketAddress, InetSocketAddress}
import scala.collection.JavaConverters._
import java.util.concurrent.{ConcurrentLinkedQueue, TimeUnit, Executors}
import io.backchat.websocket.WebSocketServer.{WebSocketServerClientHandler, WebSocketServerClient, Include}
import collection.mutable.{Buffer, SynchronizedBuffer, ListBuffer}

trait ServerCapability
case class SslSupport(
             keystorePath: String = sys.props("keystore.file.path"),
             keystorePassword: String = sys.props("keystore.file.password"),
             algorithm: String = sys.props.get("ssl.KeyManagerFactory.algorithm").flatMap(_.blankOption) | "SunX509") extends ServerCapability

case class ContentCompression(level: Int = 6) extends ServerCapability
case class SubProtocols(protocol: String, protocols: String*) extends ServerCapability

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
 *         case Connect => println("got a client connection")
 *         case TextMessage(text) => send("ECHO: " + text)
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
  type WebSocketReceive = PartialFunction[WebSocketServerClientMessage, Unit]

  sealed trait WebSocketServerClientMessage
  case object Connect extends WebSocketServerClientMessage
  case class TextMessage(content: String) extends WebSocketServerClientMessage
  case class BinaryMessage(content: Array[Byte]) extends WebSocketServerClientMessage
  case class Error(cause: Option[Throwable]) extends WebSocketServerClientMessage
  case class Disconnected(cause: Option[Throwable]) extends WebSocketServerClientMessage

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

  trait BroadcastChannel {
    def id: Int
    def send(msg: String): Unit
    def close(): Unit
  }

  private implicit def nettyChannel2BroadcastChannel(ch: Channel): BroadcastChannel =
    new { val id: Int = ch.getId } with BroadcastChannel {
      def send(msg: String) { ch.write(msg) }
      def close() { ch.close() }
    }

//  private implicit def wsServerClient2BroadcastChannel(ch: WebSocketServerClientHandler): BroadcastChannel =
//    new { val id = ch.id } with BroadcastChannel { def send(msg: String) { ch.send(msg) } }
//

  trait Broadcast {
    def apply(message: String, allowsOnly: BroadcastFilter)
  }



  private implicit def nettyChannelGroup2Broadcaster(allChannels: ChannelGroup): Broadcast = new Broadcast {
    def apply(message: String, matchingOnly: BroadcastFilter) {
      allChannels.asScala map (x => x: BroadcastChannel) filter matchingOnly foreach (_ send message)
    }
  }

  trait WebSocketServerClient extends BroadcastChannel {

    val SkipSelf = Exclude(this)
    final def id = if (_handler != null) _handler.id else 0
    final def remoteAddress = if (_handler != null) _handler.remoteAddress else null

    private[WebSocketServer] var _handler: WebSocketServerClientHandler = null
    private val _buffer = new ConcurrentLinkedQueue[String]()
    private val _broadcastBuffer = new ConcurrentLinkedQueue[(String, BroadcastFilter)]()

    /**
     * Send a text message to this client
     * @param message the message to send
     */
    final def send(message: String) = {
      if (_handler != null) {
        while(!_buffer.isEmpty) {
          val msg = _buffer.poll().blankOption
          msg foreach _handler.send
        }
        _handler send message
      } else {
        _buffer offer message
      }
    }

    /**
     * alias for [[send]] @see send
     */
    final def !(msg: String) { send(msg) }

    final def broadcast(msg: String, matchingOnly: BroadcastFilter = SkipSelf) = {
      if (_handler != null) {
        while(!_broadcastBuffer.isEmpty) {
          (_handler.broadcast _) tupled _broadcastBuffer.poll()
        }
        _handler.broadcast(msg, matchingOnly)
      } else {
        _broadcastBuffer.offer((msg, matchingOnly))
      }
    }
    final def ><(msg: String, matchingOnly: BroadcastFilter = SkipSelf) { broadcast(msg, matchingOnly) }

    def receive: WebSocketReceive

    final def close() = {
      if (_handler != null) _handler.close()
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
    final def send(message: String) = channel.send(message)

    /**
     * alias for [[send]] @see send
     */
    def !(msg: String) { send(msg) }

    final def broadcast(msg: String, matchingOnly: BroadcastFilter) = broadcaster(msg, matchingOnly)

    final def ><(msg: String, matchingOnly: BroadcastFilter) { broadcaster(msg, matchingOnly) }

    def receive: WebSocketReceive = client.receive orElse defaultReceive

    private val defaultReceive: WebSocketReceive = {
      case Error(Some(ex)) =>
        logger.error("Received an error.", ex)
      case Error(_) =>
        logger.error("Unknown error occurred")
      case _ =>
    }

    def close() = {
      channel.send("ws-do-the-close")
      channel.close()
    };
  }

  val DefaultServerName = "BackChatWebSocketServer"

  def apply(capabilities: ServerCapability*)(factory: => WebSocketServerClient): WebSocketServer = {
    apply(ServerInfo(DefaultServerName, capabilities = capabilities))(factory)
  }

  def apply(port: Int,  capabilities: ServerCapability*)(factory: => WebSocketServerClient): WebSocketServer = {
    apply(ServerInfo(DefaultServerName, port = port, capabilities = capabilities))(factory)
  }

  def apply(listenOn: String,  capabilities: ServerCapability*)(factory: => WebSocketServerClient): WebSocketServer = {
    apply(ServerInfo(DefaultServerName, listenOn = listenOn, capabilities = capabilities))(factory)
  }

  def apply(listenOn: String, port: Int,  capabilities: ServerCapability*)(factory: => WebSocketServerClient): WebSocketServer = {
    apply(ServerInfo(DefaultServerName, listenOn = listenOn, port = port, capabilities = capabilities))(factory)
  }

  def apply(name: String, listenOn: String, port: Int,  capabilities: ServerCapability*)(factory: => WebSocketServerClient): WebSocketServer = {
    apply(ServerInfo(DefaultServerName, listenOn = listenOn, port = port, capabilities = capabilities))(factory)
  }

  def apply(info: ServerInfo)(factory: => WebSocketServerClient): WebSocketServer = {
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

  private[this] object OneHundredContinueResponse extends DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE)
  private final class WebSocketClientFactoryHandler(
                                                    logger: InternalLogger,
                                                    allChannels: ChannelGroup,
                                                    factory: => WebSocketServerClient,
                                                    subProtocols: Traversable[String] = Nil) extends SimpleChannelUpstreamHandler {

    private[this] var collectedFrames: Seq[ContinuationWebSocketFrame] = Vector.empty[ContinuationWebSocketFrame]

    private[this] var handshaker: WebSocketServerHandshaker = _

    private[this] var client: WebSocketServerClientHandler = null

    private[this] implicit def newClient(ctx: ChannelHandlerContext): WebSocketServerClientHandler = {
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

        case m: TextWebSocketFrame => client.receive lift TextMessage(m.getText)

        case m: BinaryWebSocketFrame => client.receive lift BinaryMessage(m.getBinaryData.array)

        case m: ContinuationWebSocketFrame =>
          if (m.isFinalFragment) {
            client.receive lift TextMessage(collectedFrames map (_.getText) reduce (_ + _))
            collectedFrames = Nil
          } else {
            collectedFrames :+= m
          }

        case f: CloseWebSocketFrame ⇒
          if (handshaker != null) handshaker.close(ctx.getChannel, f)
          client.receive lift Disconnected(None)

        case _: PingWebSocketFrame ⇒ e.getChannel.write(new PongWebSocketFrame)

        case _ ⇒ ctx.sendUpstream(e)
      }
    }

    override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
      client.receive lift Error(Option(e.getCause))
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
        client.receive.lift(Connect)
      }
    }

    private def isHttps(req: HttpRequest) = {
      val h1 = Option(req.getHeader("REQUEST_URI")).filter(_.trim.nonEmpty)
      val h2 = Option(req.getHeader("REQUEST_URI")).filter(_.trim.nonEmpty)
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

  class WebSocketMessageAdapter(logger: InternalLogger) extends SimpleChannelDownstreamHandler {
    override def writeRequested(ctx: ChannelHandlerContext, e: MessageEvent) {
      e.getMessage match {
        case "ws-do-the-close" => {
          ctx.getChannel.write(new CloseWebSocketFrame()).addListener(ChannelFutureListener.CLOSE)
        }
        case text: String => ctx.getChannel.write(new TextWebSocketFrame(text))
        case TextMessage(text) => ctx.getChannel.write(new TextWebSocketFrame(text))
        case BinaryMessage(bytes) => ctx.getChannel.write(new BinaryWebSocketFrame(ChannelBuffers.copiedBuffer(bytes)))
        case _ => ctx.sendDownstream(e)
      }
    }
  }

}

class WebSocketServer(val config: ServerInfo, factory: => WebSocketServerClient) {
  def capabilities = config.capabilities
  def name = config.name
  def version = config.version
  def listenOn = config.listenOn
  def port = config.port


  import WebSocketServer._
  protected val logger = InternalLoggerFactory.getInstance(getClass)
  private[this] val boss = Executors.newCachedThreadPool()
  private[this] val worker = Executors.newCachedThreadPool()
  private[this] val server = {
    val bs = new ServerBootstrap(new NioServerSocketChannelFactory(boss, worker))
    bs.setOption("soLinger", 0)
    bs.setOption("reuseAddress", true)
    bs.setOption("child.tcpNoDelay", true)
    bs
  }

  private[this] val allChannels = new DefaultChannelGroup

  protected def getPipeline = {
    val pipe = Channels.pipeline()
    pipe.addLast("connection-tracker", new ConnectionTracker(allChannels))
    config.sslContext foreach { ctxt =>
      val engine = ctxt.createSSLEngine()
      engine.setUseClientMode(false)
      pipe.addLast("ssl", new SslHandler(engine))
    }
    pipe.addLast("decoder", new HttpRequestDecoder(4096, 8192, 8192))
    pipe.addLast("aggregator", new HttpChunkAggregator(64 * 1024))
    pipe.addLast("encoder", new HttpResponseEncoder)
    config.contentCompression foreach { ctx =>
      pipe.addLast("deflater", new HttpContentCompressor(ctx.level))
    }
    pipe.addLast("websockethandler", new WebSocketClientFactoryHandler(logger, allChannels, factory))
    pipe.addLast("websocketoutput", new WebSocketMessageAdapter(logger))
    pipe
  }

  private[this] val startCallbacks = new ListBuffer[() => Any]()
  private[this] val stopCallbacks = new ListBuffer[() => Any]()

  def onStart(thunk: => Any) = startCallbacks += { () => thunk }
  def onStop(thunk: => Any) = stopCallbacks += { () => thunk }

  final def start = synchronized {
    server.setPipeline(getPipeline)
    val addr = config.listenOn.blankOption.map(l =>new InetSocketAddress(l, config.port) ) | new InetSocketAddress(config.port)
    val sc = server.bind(addr)
    allChannels add sc
    startCallbacks foreach (_.apply())
    sys.addShutdownHook(stop)
    logger info "Started %s on [%s:%d]".format(config.name, config.listenOn, config.port)
  }

  final def stop = synchronized {
    stopCallbacks foreach (_.apply())
    allChannels.close().awaitUninterruptibly()
    val thread = new Thread {
      override def run = {
        server.releaseExternalResources()
        boss.awaitTermination(5, TimeUnit.SECONDS)
        worker.awaitTermination(5, TimeUnit.SECONDS)
      }
    }
    thread.setDaemon(false)
    thread.start()
    thread.join()
    logger info "Stopped %s".format(config.name)
  }
}