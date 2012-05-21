package io.backchat.hookup

import org.jboss.netty.bootstrap.ServerBootstrap
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory
import org.jboss.netty.channel._
import group.{ ChannelGroup, DefaultChannelGroup }
import org.jboss.netty.handler.codec.http.websocketx._
import java.util.Locale.ENGLISH
import org.jboss.netty.logging.{ InternalLogger, InternalLoggerFactory }
import java.security.KeyStore
import java.io.{ FileInputStream, File }
import javax.net.ssl.{ KeyManagerFactory, SSLContext }
import org.jboss.netty.handler.ssl.SslHandler
import org.jboss.netty.handler.codec.http._
import java.net.{ SocketAddress, InetSocketAddress }
import scala.collection.JavaConverters._
import collection.mutable.ListBuffer
import akka.util.duration._
import org.jboss.netty.handler.timeout.{ IdleStateEvent, IdleState, IdleStateHandler, IdleStateAwareChannelHandler }
import net.liftweb.json._
import JsonDSL._
import java.util.concurrent.{ ConcurrentHashMap, ConcurrentLinkedQueue, TimeUnit, Executors }
import _root_.io.backchat.hookup.HookupServer.{ WebSocketCancellable }
import akka.util.{ Index, Timeout }
import annotation.switch
import org.jboss.netty.handler.codec.frame.FrameDecoder
import org.jboss.netty.handler.codec.http.HttpVersion.HTTP_1_1
import org.jboss.netty.handler.codec.http.HttpResponseStatus.OK
import org.jboss.netty.buffer.{ ChannelBuffer, ChannelBuffers }
import org.jboss.netty.util.{ CharsetUtil, Timeout ⇒ NettyTimeout, TimerTask, HashedWheelTimer }
import com.typesafe.config.Config
import akka.actor.{Actor, ActorRef, DefaultCancellable, Cancellable}
import akka.config.ConfigurationException
import org.jboss.netty.handler.codec.http.HttpHeaders.Names._
import org.jboss.netty.handler.codec.http.HttpHeaders._
import java.text.SimpleDateFormat
import java.util.{Date, TimeZone}
import java.util.logging.{Level, Logger}
import akka.dispatch.{ExecutionContext, Promise, Future}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference, AtomicLong}

/**
 * A marker trait to indicate something is a a configuration for the server.
 */
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

/**
 * Configuration for content compression
 *
 * @param level the compression level
 */
case class ContentCompression(level: Int = 6) extends ServerCapability

/**
 * The subprotocols this server can respond to.
 *
 * @param protocol A supported protocol name
 * @param protocols remaining supported protocols
 */
case class SubProtocols(protocol: WireFormat, protocols: WireFormat*) extends ServerCapability

/**
 * The configuration for sending pings to a client. (Some websocket clients don't support ping frames)
 *
 * @param timeout the timeout for a connection to be idle before sending a ping.
 */
case class Ping(timeout: Timeout) extends ServerCapability

/**
 * The configuration for the flash policy xml
 *
 * @param domain the domain to accept connections for
 * @param ports the ports to accept connections on
 */
case class FlashPolicy(domain: String, ports: Seq[Int]) extends ServerCapability

case class MaxFrameSize(size: Long = Long.MaxValue) extends ServerCapability

/**
 * Private object used in unit tests
 */
private[hookup] case object RaiseAckEvents extends ServerCapability

/**
 * Private object used in unit tests
 */
private[hookup] case object RaisePingEvents extends ServerCapability

/**
 * @see [[io.backchat.hookup.ServerInfo]]
 */
object ServerInfo {
  /**
   * The default server name ''BackChat.io Hookup Server''
   */
  val DefaultServerName = "BackChat.io Hookup Server"

  /**
   * Creates a [[io.backchat.hookup.ServerInfo]] with the [[io.backchat.hookup.ServerInfo.DefaultServerName]]
   *
   * @param config A [[com.typesafe.config.Config]] object
   * @return the created [[io.backchat.hookup.ServerInfo]]
   */
  def apply(config: Config): ServerInfo = apply(config, DefaultServerName, DefaultProtocols)

  /**
   * Creates a [[io.backchat.hookup.ServerInfo]] with the [[io.backchat.hookup.ServerInfo.DefaultServerName]]
   *
   * @param config A [[com.typesafe.config.Config]] object
   * @param protocols A [[scala.collection.Map]] of string keys and wireformats with the supported formats
   * @return the created [[io.backchat.hookup.ServerInfo]]
   */
  def apply(config: Config, protocols: Seq[WireFormat]): ServerInfo =
    apply(config, DefaultServerName, protocols)

  /**
   * Creates a [[io.backchat.hookup.ServerInfo]]
   *
   * @param config A [[com.typesafe.config.Config]] object
   * @param name the name of the server
   * @return the created [[io.backchat.hookup.ServerInfo]]
   */
  def apply(config: Config, name: String): ServerInfo =
    apply(config, name, DefaultProtocols)

  /**
   * Creates a [[io.backchat.hookup.ServerInfo]]
   *
   * @param config A [[com.typesafe.config.Config]] object
   * @param name the name of the server
   * @param protocols A [[scala.collection.Map]] of string keys and wireformats with the supported formats
   * @return the created [[io.backchat.hookup.ServerInfo]]
   */
  def apply(config: Config, name: String, protocols: Seq[WireFormat]): ServerInfo = {
    import collection.JavaConverters._

    val allProtos = DefaultProtocols.filterNot(p => protocols.exists(_.name == p.name)) ++ protocols
    val caps = ListBuffer[ServerCapability]()
    if (config.hasPath("contentCompression"))
      caps += ContentCompression(config.getInt("contentCompression"))

    if (config.hasPath("subProtocols")) {
      val lst = config.getStringList("subProtocols").asScala.toList
      if (lst.nonEmpty)
        caps += SubProtocols(allProtos.head, allProtos.tail:_*)
    }

    if (config.hasPath("pingTimeout"))
      caps += Ping(Timeout(config.getMilliseconds("pingTimeout")))

    if (config.hasPath("flashPolicy")) {
      val domain = if (config.hasPath("flashPolicy.domain")) config.getString("flashPolicy.domain") else "*"
      val ports: List[Int] = if (config.hasPath("flashPolicy.ports")) config.getIntList("flashPolicy.ports").asScala.map(_.toInt).toList else {
        if (config.hasPath("flashPolicy.port")) List(config.getInt("flashPolicy.port")) else Nil
      }
      caps += FlashPolicy(domain, ports)
    }

    if (config.hasPath("ssl")) {
      val keystore = if (config.hasPath("ssl.keystore")) config.getString("ssl.keystore")
      else sys.props.get("keystore.file.path").getOrElse(throw new RuntimeException("You need to specify a keystore."))
      val passw = if (config.hasPath("ssl.password")) config.getString("ssl.password")
      else sys.props.get("keystore.file.password").getOrElse(throw new RuntimeException("You need to specify a password for the keystore"))
      val algo = if (config.hasPath("ssl.algorithm")) config.getString("ssl.algorithm")
      else (sys.props.get("ssl.KeyManagerFactory.algorithm").flatMap(_.blankOption) | "SunX509")
      caps += SslSupport(keystore, passw, algo)
    }

    caps += (if (config.hasPath("maxFrameSize")) MaxFrameSize(config.getBytes("maxFrameSize")) else MaxFrameSize())

    new ServerInfo(
      name,
      if (config.hasPath("version")) config.getString("version") else BuildInfo.version,
      if (config.hasPath("listenOn")) config.getString("listenOn") else "0.0.0.0",
      if (config.hasPath("port")) config.getInt("port") else 8765,
      if (config.hasPath("defaultProtocol")) config.getString("defaultProtocol") else DefaultProtocol,
      caps)
  }
}

/**
 * Main configuration object for a server
 *
 * @param name The name of this server, defaults to BackchatWebSocketServer
 * @param version The version of the server
 * @param listenOn Which address the server should listen on
 * @param port The port the server should listen on
 * @param defaultProtocol The default protocol for this server to use when no subprotocols have been specified
 * @param capabilities A sequence of [[io.backchat.hookup.ServerCapability]] configurations for this server
 */
/// code_ref: server_info
case class ServerInfo(
    name: String = ServerInfo.DefaultServerName,
    version: String = BuildInfo.version,
    listenOn: String = "0.0.0.0",
    port: Int = 8765,
    defaultProtocol: String = DefaultProtocol,
    capabilities: Seq[ServerCapability] = Seq.empty,
    executionContext: ExecutionContext = HookupClient.executionContext) {
/// end_code_ref
  /**
   * If the server should support SSL this will be filled with the ssl context to use
   */
  val sslContext: Option[SSLContext] = (capabilities collect {
    case cfg: SslSupport ⇒ {
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

  /**
   * If the server should support content compression this will have the configuration for it.
   */
  val contentCompression: Option[ContentCompression] =
    (capabilities collect { case c: ContentCompression ⇒ c }).headOption

  /**
   * If the server should support sub-protocols this will have the configuration for it.
   */
  val protocols = DefaultProtocols ++ ((capabilities collect {
    case sp: SubProtocols => Seq(sp.protocol) ++ sp.protocols
  }).headOption getOrElse Seq.empty)

  val defaultWireFormat =
    protocols.find(_.name == defaultProtocol) getOrElse (throw new RuntimeException("Invalid default protocol."))

  /**
   * If the server should support pinging this will have the configuration for it.
   */
  val pingTimeout = (capabilities collect {
    case Ping(timeout) ⇒ timeout
  }).headOption

  /**
   * If the server should support max frame sizes for websocket frames this will have the configuration for it.
   */
  val maxFrameSize: Long = (capabilities collect {
    case MaxFrameSize(size) => size
  }).headOption | Long.MaxValue

  /**
   * The configuration for the flash policy, by default it allows all..
   */
  val flashPolicy = (capabilities collect {
    case FlashPolicy(domain, policyPorts) ⇒
      (<cross-domain-policy>
         <allow-access-from domain={ domain } to-ports={ policyPorts.mkString(",") }/>
       </cross-domain-policy>).toString()
  }).headOption getOrElse {
    (<cross-domain-policy>
       <allow-access-from domain="*" to-ports="*"/>
     </cross-domain-policy>).toString()
  }
}


/**
 * @see [[io.backchat.hookup.HookupServer]]
 */
object HookupServer {

  import ServerInfo.DefaultServerName
  //  type WebSocketHandler = PartialFunction[WebSocketMessage, Unit]

  import HookupClient.{ Receive }

  /**
   * A filter for broadcast channels, a predicate that can't be null
   */
  trait BroadcastFilter extends (BroadcastChannel ⇒ Boolean) with NotNull

  /**
   * Companion object for [[io.backchat.hookup.HookupServer.Include]]
   */
  object Include {
    /**
     * Create an include filter from varargs
     *
     * @param clients The available clients to filter
     * @return a [[io.backchat.hookup.HookupServer.BroadcastFilter]]
     */
    def apply(clients: HookupServerClient*): BroadcastFilter = new Include(clients)
  }

  /**
   * An include filter, if the channel exists in the list of open connections it will match
   *
   * @param clients The open connections
   */
  class Include(clients: Seq[HookupServerClient]) extends BroadcastFilter {

    /**
     * Execute the matcher against the provided channel
     *
     * @param channel The [[io.backchat.hookup.BroadcastChannel]]
     * @return A [[scala.Boolean]] indicating success or failure
     */
    def apply(channel: BroadcastChannel) = clients.exists(_.id == channel.id)
  }

  /**
   * Companion object for [[io.backchat.hookup.HookupServer.Exclude]]
   */
  object Exclude {

    /**
     * Create an exclude filter from varargs
     *
     * @param clients The available clients to filter
     * @return a [[io.backchat.hookup.HookupServer.BroadcastFilter]]
     */
    def apply(clients: HookupServerClient*): BroadcastFilter = new Exclude(clients)
  }

  /**
   * An exclude filter, if the channel does not exists in the list of open connections it will match
   *
   * @param clients The open connections
   */
  class Exclude(clients: Seq[HookupServerClient]) extends BroadcastFilter {
    /**
     * Execute the matcher against the provided channel
     *
     * @param channel The [[io.backchat.hookup.BroadcastChannel]]
     * @return A [[scala.Boolean]] indicating success or failure
     */
    def apply(channel: BroadcastChannel) = !clients.exists(_.id == channel.id)
  }

  /**
   * A convenience mixin for using an actor as an event handler
   */
  trait HookupClientActor { self: Actor =>
    /**
     * The actual websocket connection.
     *
     * @return A [[io.backchat.hookup.HookupServerClient]]
     */
    protected def connection: HookupServerClient

    /**
     * The event handler for websocket events.
     *
     * @return The partial function to handle inbound websocket messages.
     */
    protected def remoteReceive: Actor.Receive
  }

  /**
   * A convenience trait for bridging a websocket to an actor.
   */
  trait ActorHookupServerClient { self: HookupServerClient =>

    /**
     * The factory to use to create the actor handler
     *
     * @return A function that takes a [[io.backchat.hookup.HookupServerClient]] and returns an [[akka.actor.ActorRef]]
     */
    protected def actorFactory: HookupServerClient => ActorRef

    /**
     * A lazy value of the actor being linked to.
     */
    lazy val linkedTo: ActorRef = actorFactory(this)

    /**
     * The message event handler, defers creating the linked actor until the first message is received
     */
    val receive: Actor.Receive = {
      case m => linkedTo ! m
    }
  }

  /**
   * Represents a broadcast operation.
   */
  trait Broadcast {
    def apply(message: OutboundMessage, allowsOnly: BroadcastFilter): Future[OperationResult]
  }

  private implicit def nettyChannelGroup2Broadcaster(allChannels: ChannelGroup)(implicit exCtxt: ExecutionContext): Broadcast = new Broadcast {
    def apply(message: OutboundMessage, matchingOnly: BroadcastFilter) = {
      val lst = allChannels.asScala map (x => x: BroadcastChannel) filter matchingOnly map (_ send message)
      Future.sequence(lst) map (l ⇒ ResultList(l.toList))
    }
  }

  /**
   * The interface library users use when implementing a websocket server to represent a client.
   * For every new connection made to the server it will create one of these guys.
   *
   * You can use it to maintain state for your client, but be aware that multiple threads maybe accessing the state
   * at the same time so you should take care of thread safety.
   */
  trait HookupServerClient extends BroadcastChannel {

    /**
     * The default broadcast filter broadcast operations use, it skips publishing to the sending channel
     */
    val SkipSelf = Exclude(this)


    final def id = if (_handler != null) _handler.id else 0
    final def remoteAddress = if (_handler != null) _handler.remoteAddress else null
    protected implicit final def executionContext: ExecutionContext = if (_handler != null) _handler.executor else null

    private[HookupServer] var _handler: HookupServerClientHandler = null
    private val _buffer = new ConcurrentLinkedQueue[OutboundMessage]()
    private val _broadcastBuffer = new ConcurrentLinkedQueue[(OutboundMessage, BroadcastFilter)]()


    /**
     * Send a text message to this client
     * @param message the message to send
     */
    final def send(message: OutboundMessage): Future[OperationResult] = {
      if (_handler != null) {
        val futures = new ListBuffer[Future[OperationResult]]
        while (!_buffer.isEmpty) {
          futures += _handler.send(_buffer.poll())
        }
        futures += _handler send message
        Future.sequence(futures).map(r ⇒ ResultList(r.toList))
      } else {
        _buffer offer message
        Promise.successful(Success)
      }
    }

    /**
     * alias for [[io.backchat.hookup.HookupServer.HookupServerClient.send]]
     * @see [[io.backchat.hookup.HookupServer.HookupServerClient.send]]
     */
    final def !(msg: OutboundMessage) { send(msg) }

    /**
     * Broadcast this message to all connections matching the filter
     *
     * @param msg The [[io.backchat.hookup.OutboundMessage]] to broadcast
     * @param onlyTo The filter to determine the connections to send to. Defaults to all but self.
     * @return A [[akka.dispatch.Future]] with the [[io.backchat.hookup.OperationResult]]
     */
    final def broadcast(msg: OutboundMessage, onlyTo: BroadcastFilter = SkipSelf): Future[OperationResult] = {
      if (_handler != null) {
        val futures = new ListBuffer[Future[OperationResult]]
        while (!_broadcastBuffer.isEmpty) {
          futures += (((_handler.broadcast _) tupled) apply _broadcastBuffer.poll())
        }
        futures += _handler.broadcast(msg, onlyTo)
        Future.sequence(futures.toList) map ResultList.apply
      } else {
        _broadcastBuffer.offer((msg, onlyTo))
        Promise.successful(Success)
      }
    }

    /**
     * Alias for [[io.backchat.hookup.HookupServer.HookupServerClient.broadcast]]
     * @see [[io.backchat.hookup.HookupServer.HookupServerClient.broadcast]]
     */
    final def ><(msg: OutboundMessage, onlyTo: BroadcastFilter = SkipSelf): Future[OperationResult] = broadcast(msg, onlyTo)

    /**
     * Abstract method to implement a handler for inbound messages
     * @return a [[io.backchat.hookup.HookupClient.Receive]] handler
     */
    def receive: Receive

    final def disconnect() = {
      if (_handler != null) _handler.close()
      else Promise.successful(Success)
    }

  }

  /**
   * Represents a client connection handle to this server
   */
  private abstract class HookupServerClientHandler(channel: BroadcastChannel, client: HookupServerClient, logger: InternalLogger, broadcaster: Broadcast)(implicit val executor: ExecutionContext) {

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
    final def send(message: OutboundMessage) = channel.send(message)

    /**
     * alias for [[io.backchat.hookup.HookupServer.HookupServerClientHandler.send]]
     * @see [[io.backchat.hookup.HookupServer.HookupServerClientHandler.send]]
     */
    def !(msg: OutboundMessage) { send(msg) }

    /**
     * Broadcast this message to all connections matching the filter
     *
     * @param msg The [[io.backchat.hookup.OutboundMessage]] to broadcast
     * @param matchingOnly The filter to determine the connections to send to. Defaults to all but self.
     * @return A [[akka.dispatch.Future]] with the [[io.backchat.hookup.OperationResult]]
     */
    final def broadcast(msg: OutboundMessage, matchingOnly: BroadcastFilter) = broadcaster(msg, matchingOnly)

    /**
     * alias for [[io.backchat.hookup.HookupServer.HookupServerClientHandler.send]]
     * @see [[io.backchat.hookup.HookupServer.HookupServerClientHandler.send]]
     */
    final def ><(msg: OutboundMessage, matchingOnly: BroadcastFilter) { broadcaster(msg, matchingOnly) }

    /**
     * Abstract method to implement a handler for inbound messages
     * @return a [[io.backchat.hookup.HookupClient.Receive]] handler
     */
    def receive: Receive = client.receive orElse defaultReceive

    private val defaultReceive: Receive = {
      case Error(Some(ex)) ⇒
        logger.error("Received an error.", ex)
      case Error(_) ⇒
        logger.error("Unknown error occurred")
      case m ⇒
        logger.warn("Unhandled message: %s" format m)
    }

    def close() = {
      channel.send(Disconnect)
    };
  }


  /**
   * Creates a [[io.backchat.hookup.HookupServer]] with the specified params
   *
   * @param capabilities The a varargs sequence of [[io.backchat.hookup.ServerCapability]] objects to configure this server with
   * @param factory The factor for creating the [[io.backchat.hookup.HookupServerClient]] instances
   * @return A [[io.backchat.hookup.HookupServer]]
   */
  def apply(capabilities: ServerCapability*)(factory: ⇒ HookupServerClient): HookupServer = {
    apply(ServerInfo(DefaultServerName, capabilities = capabilities))(factory)
  }

  /**
   * Creates a [[io.backchat.hookup.HookupServer]] with the specified params
   *
   * @param port The port this server will listen on.
   * @param capabilities The a varargs sequence of [[io.backchat.hookup.ServerCapability]] objects to configure this server with
   * @param factory The factor for creating the [[io.backchat.hookup.HookupServerClient]] instances
   * @return A [[io.backchat.hookup.HookupServer]]
   */
  def apply(port: Int, capabilities: ServerCapability*)(factory: ⇒ HookupServerClient): HookupServer = {
    apply(ServerInfo(DefaultServerName, port = port, capabilities = capabilities))(factory)
  }

  /**
   * Creates a [[io.backchat.hookup.HookupServer]] with the specified params
   *
   * @param listenOn The host/network address this server will listen on
   * @param capabilities The a varargs sequence of [[io.backchat.hookup.ServerCapability]] objects to configure this server with
   * @param factory The factor for creating the [[io.backchat.hookup.HookupServerClient]] instances
   * @return A [[io.backchat.hookup.HookupServer]]
   */
  def apply(listenOn: String, capabilities: ServerCapability*)(factory: ⇒ HookupServerClient): HookupServer = {
    apply(ServerInfo(DefaultServerName, listenOn = listenOn, capabilities = capabilities))(factory)
  }

  /**
   * Creates a [[io.backchat.hookup.HookupServer]] with the specified params
   *
   * @param listenOn The host/network address this server will listen on
   * @param port The port this server will listen on.
   * @param capabilities The a varargs sequence of [[io.backchat.hookup.ServerCapability]] objects to configure this server with
   * @param factory The factor for creating the [[io.backchat.hookup.HookupServerClient]] instances
   * @return A [[io.backchat.hookup.HookupServer]]
   */
  def apply(listenOn: String, port: Int, capabilities: ServerCapability*)(factory: ⇒ HookupServerClient): HookupServer = {
    apply(ServerInfo(DefaultServerName, listenOn = listenOn, port = port, capabilities = capabilities))(factory)
  }

  /**
   * Creates a [[io.backchat.hookup.HookupServer]] with the specified params
   *
   * @param name The name of this server
   * @param listenOn The host/network address this server will listen on
   * @param port The port this server will listen on.
   * @param capabilities The a varargs sequence of [[io.backchat.hookup.ServerCapability]] objects to configure this server with
   * @param factory The factor for creating the [[io.backchat.hookup.HookupServerClient]] instances
   * @return A [[io.backchat.hookup.HookupServer]]
   */
  def apply(name: String, listenOn: String, port: Int, capabilities: ServerCapability*)(factory: ⇒ HookupServerClient): HookupServer = {
    apply(ServerInfo(DefaultServerName, listenOn = listenOn, port = port, capabilities = capabilities))(factory)
  }

  /**
   * Creates a [[io.backchat.hookup.HookupServer]] with the specified params
   *
   * @param info The [[io.backchat.hookup.ServerInfo]] to use to configure this server
   * @param factory The factor for creating the [[io.backchat.hookup.HookupServerClient]] instances
   * @return A [[io.backchat.hookup.HookupServer]]
   */
  def apply(info: ServerInfo)(factory: ⇒ HookupServerClient): HookupServer = {
    new HookupServer(info, factory)
  }

  /**
   * Keep track of the open connections
   * @param channels The open connections
   */
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

  private[this] val PolicyXml = <cross-domain-policy><allow-access-from domain="*" to-ports="*"/></cross-domain-policy>
  private val AllowAllPolicy = ChannelBuffers.copiedBuffer(PolicyXml.toString(), CharsetUtil.UTF_8)

  /**
   * A flash policy handler for netty. This needs to be included in the pipeline before anything else has touched
   * the message.
   *
   * @see [[https://github.com/cgbystrom/netty-tools/blob/master/src/main/java/se/cgbystrom/netty/FlashPolicyHandler.java]]
   * @param policyResponse The response xml to send for a request
   */
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

  /**
   * A 100 Continue response
   */
  private[this] object OneHundredContinueResponse extends DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE)

  private final class WebSocketClientFactoryHandler(logger: InternalLogger,
      allChannels: ChannelGroup,
      factory: ⇒ HookupServerClient,
      defaultWireFormat: WireFormat,
      subProtocols: Map[String, WireFormat] = Map.empty,
      maxFrameSize: Long = Long.MaxValue,
      raiseEvents: Boolean = false)(implicit executionContext: ExecutionContext) extends SimpleChannelHandler {

    private[this] var collectedFrames: Seq[ContinuationWebSocketFrame] = Vector.empty[ContinuationWebSocketFrame]

    private[this] var handshaker: WebSocketServerHandshaker = _

    private[this] var client: HookupServerClientHandler = null

    private[this] var receivedCloseFrame: Boolean = false

    private[this] val wireFormat = new AtomicReference[WireFormat](defaultWireFormat)

    private[this] def clientFrom(ctx: ChannelHandlerContext): HookupServerClientHandler = {
      (Option(ctx.getAttachment) collect {
        case h: HookupServerClientHandler ⇒ h
      }) getOrElse {
        val sockAddr = ctx.getChannel.getRemoteAddress
        val h = new HookupServerClientHandler(ctx.getChannel, factory, logger, allChannels) {
          val remoteAddress = sockAddr
        }
        ctx.setAttachment(h)
        h
      }
    }

    private def isSubProto(req: HttpRequest) =
      req.getHeader(Names.SEC_WEBSOCKET_PROTOCOL) != null && subProtocols.contains(spFromReq(req))
    private def spFromReq(req: HttpRequest) = req.getHeader(Names.SEC_WEBSOCKET_PROTOCOL).toString

    override def writeRequested(ctx: ChannelHandlerContext, e: MessageEvent) {
      e.getMessage match {
        case req: HttpRequest if isSubProto(req) =>
          val wf = subProtocols(spFromReq(req))
          wireFormat.set(wf)
          Channels.fireMessageReceived(e.getChannel, SelectedWireFormat(wf))
        case _ =>
      }
      super.writeRequested(ctx, e)
    }

    override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
      e.getMessage match {
        case request: HttpRequest if HttpHeaders.is100ContinueExpected(request) ⇒
          Channels.write(ctx, Channels.future(ctx.getChannel), OneHundredContinueResponse, e.getRemoteAddress)
          request.removeHeader(HttpHeaders.Names.EXPECT)
          ctx.sendUpstream(e)

        case httpRequest: HttpRequest if isWebSocketUpgrade(httpRequest) ⇒ handleUpgrade(ctx, httpRequest)

        case m: TextWebSocketFrame ⇒ {
          wireFormat.get.parseInMessage(m.getText) match {
            case a: Ack        ⇒ Channels.fireMessageReceived(ctx, a)
            case a: AckRequest ⇒ Channels.fireMessageReceived(ctx, a)
            case r             ⇒ client.receive lift r
          }
        }

        case m: BinaryWebSocketFrame ⇒ client.receive lift BinaryMessage(m.getBinaryData.array)

        case m: ContinuationWebSocketFrame ⇒
          if (m.isFinalFragment) {
            client.receive lift TextMessage(collectedFrames map (_.getText) reduce (_ + _))
            collectedFrames = Nil
          } else {
            collectedFrames :+= m
          }

        case f: InboundMessage ⇒ client.receive lift f

        case f: CloseWebSocketFrame ⇒
          receivedCloseFrame = true
          if (handshaker != null) handshaker.close(ctx.getChannel, f)

        case _: PingWebSocketFrame ⇒ e.getChannel.write(new PongWebSocketFrame)

        case _: PongWebSocketFrame => // drop, all is well

        case _                     ⇒ ctx.sendUpstream(e)
      }
    }

    override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
      if (client != null && client.receive != null) client.receive lift Error(Option(e.getCause))
      else logger.error("Exception during connection.", e.getCause)
    }

    override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
      if (client != null) {
        client.receive lift Disconnected(None)
        client = null
      }
      ctx.setAttachment(null)
    }

    private def isWebSocketUpgrade(httpRequest: HttpRequest): Boolean = {
      val connHdr = httpRequest.getHeader(Names.CONNECTION)
      val upgrHdr = httpRequest.getHeader(Names.UPGRADE)
      (connHdr != null && connHdr.equalsIgnoreCase(Values.UPGRADE)) &&
        (upgrHdr != null && upgrHdr.equalsIgnoreCase(Values.WEBSOCKET))
    }

    private def handleUpgrade(ctx: ChannelHandlerContext, httpRequest: HttpRequest) {
      val protos = if (subProtocols.isEmpty) null else subProtocols.map(_._1).mkString(",")
      println("protos: %s" format protos)
      val handshakerFactory = new WebSocketServerHandshakerFactory(websocketLocation(httpRequest), protos, false, maxFrameSize)
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

  /**
   * Uses the [[io.backchat.hookup.WireFormat]]] to serialize outgoing messages.
   * It serializes the message and then writes it as a text websocket frame to the connection
   *
   * @param logger The [[org.jboss.netty.logging.InternalLogger]] to use in this adapter
   * @param defaultWireFormat The default [[io.backchat.hookup.WireFormat]] to serialize messages with.
   */
  class WebSocketMessageAdapter(logger: InternalLogger, defaultWireFormat: WireFormat) extends SimpleChannelDownstreamHandler {

    private[this] val wireFormat = new AtomicReference[WireFormat](defaultWireFormat)
    override def writeRequested(ctx: ChannelHandlerContext, e: MessageEvent) {
      e.getMessage match {
        case SelectedWireFormat(wf) => wireFormat.set(wf)
        case _ if wireFormat.get == null => throw new IllegalStateException("Can't handle messages without a wireformat")
        case m: JsonMessage ⇒ writeOutMessage(ctx, m)
        case m: TextMessage ⇒ writeOutMessage(ctx, m)
        case Disconnect     ⇒ ctx.getChannel.write(new CloseWebSocketFrame()).addListener(ChannelFutureListener.CLOSE)
        case BinaryMessage(bytes) ⇒ {
          ctx.getChannel.write(new BinaryWebSocketFrame(ChannelBuffers.copiedBuffer(bytes)))
        }
        case _ ⇒ {
          ctx.sendDownstream(e)
        }
      }
    }

    private def writeOutMessage(ctx: ChannelHandlerContext, msg: OutboundMessage) {
      ctx.getChannel.write(new TextWebSocketFrame(wireFormat.get.render(msg)))
    }
  }

  /**
   * implementation of an [[akka.actor.Cancellable]] with a [[org.jboss.netty.util.Timeout]]
   * @param timeout a [[org.jboss.netty.util.Timeout]]
   */
  private class WebSocketCancellable(timeout: NettyTimeout) extends Cancellable {
    def cancel() {
      timeout.cancel()
    }

    def isCancelled = timeout.isCancelled

  }

  /**
   * Responds to ack requests as they are received, and forwards on the inbound message.
   *
   * @param logger The [[org.jboss.netty.logging.InternalLogger]] to use in this adapter
   * @param defaultWireFormat The default [[io.backchat.hookup.WireFormat]] to serialize messages with.
   * @param raiseEvents A boolean flag to raise events or not, only valuable during testing.
   */
  class MessageAckingHandler(logger: InternalLogger, defaultWireFormat: WireFormat, raiseEvents: Boolean = false) extends SimpleChannelHandler {

    private[this] val messageCounter = new AtomicLong
    private[this] val expectedAcks = new ConcurrentHashMap[Long, Cancellable]()
    private[this] val ackScavenger = new HashedWheelTimer()
    private[this] val wireFormat = new AtomicReference[WireFormat](defaultWireFormat)

    override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
      e.getMessage match {
        case SelectedWireFormat(wf) => wireFormat.set(wf)
        case _ if wireFormat.get == null => throw new IllegalStateException("Can't handle messages without a wireformat")
        case Ack(id) if wireFormat.get() != null && wireFormat.get().supportsAck ⇒
          val ack = expectedAcks.remove(id)
          if (ack != null) ack.cancel()
          if (raiseEvents) ctx.sendUpstream(e)
        case Ack(id) if wireFormat.get() != null && !wireFormat.get().supportsAck ⇒
          logger.warn("Trying to ack over a wire format that doesn't support acking.")
          if (raiseEvents) ctx.sendUpstream(e)
        case AckRequest(msg, id) if wireFormat.get() != null && wireFormat.get().supportsAck ⇒ {
          ctx.getChannel.write(Ack(id))
          if (raiseEvents) Channels.fireMessageReceived(ctx, AckRequest(msg, id))
          Channels.fireMessageReceived(ctx, msg)
        }
        case AckRequest(msg, id) if wireFormat.get() != null && !wireFormat.get().supportsAck ⇒ {
          ctx.getChannel.write(Ack(id))
          if (raiseEvents) Channels.fireMessageReceived(ctx, AckRequest(msg, id))
          Channels.fireMessageReceived(ctx, msg)
        }
        case _ ⇒ ctx.sendUpstream(e)
      }
    }

    override def writeRequested(ctx: ChannelHandlerContext, e: MessageEvent) {
      e.getMessage match {
        case _ if wireFormat.get == null => throw new IllegalStateException("Can't render messages without a wireformat")
        case m: Ack if wireFormat.get != null && wireFormat.get.supportsAck ⇒
          ctx.getChannel.write(new TextWebSocketFrame(wireFormat.get.render(m)))
        case m: Ack if wireFormat.get != null && !wireFormat.get.supportsAck ⇒
          logger.warn("Trying to ack over a wire format that doesn't support acking, ack message dropped.")
        case NeedsAck(m, timeout) if wireFormat.get != null && wireFormat.get.supportsAck ⇒
          val id = createAck(ctx, m, timeout)
          if (raiseEvents) Channels.fireMessageReceived(ctx, AckRequest(m, id))
        case NeedsAck(m, timeout) if wireFormat.get != null && !wireFormat.get.supportsAck ⇒
          logger.warn("Trying to ack over a wire format that doesn't support acking, ack message dropped.")
          if (raiseEvents) Channels.fireMessageReceived(ctx, AckRequest(m, -1))
        case _ ⇒ ctx.sendDownstream(e)
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
          if (!timeout.isCancelled) Channels.fireMessageReceived(ctx, AckFailed(message.asInstanceOf[OutboundMessage]))
        }
      }, timeout.duration.toMillis, TimeUnit.MILLISECONDS)
      val exp = new WebSocketCancellable(to)
      while (expectedAcks.put(id, exp) != null) { // spin until we've updated

      }
      ctx.getChannel.write(new TextWebSocketFrame(compact(render(msg))))
      id
    }


    override def channelDisconnected(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
      ackScavenger.stop.asScala foreach (_.cancel())
      super.channelDisconnected(ctx, e)
    }

    override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
      ackScavenger.stop.asScala foreach (_.cancel())
      super.channelClosed(ctx, e)
    }

    override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
      ackScavenger.stop.asScala foreach (_.cancel())
      super.exceptionCaught(ctx, e)
    }

    private[this] def contentFrom(message: Ackable): (String, JValue) = message match {
      case TextMessage(text) ⇒ ("text", JString(text))
      case JsonMessage(json) ⇒ ("json", json)
    }
  }



  /**
   * A http request handler that responses to `ping` requests with the word `pong` for the specified path
   *
   * @param path The path for the ping endpoint
   */
  class LoadBalancerPing(path: String) extends SimpleChannelUpstreamHandler {
    override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
      e.getMessage match {
        case r: HttpRequest if r.getUri.toLowerCase.startsWith(path) =>
          val res = new DefaultHttpResponse(HTTP_1_1, OK)
          val content = ChannelBuffers.copiedBuffer("pong", CharsetUtil.UTF_8)
          val dateformat = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz")
          dateformat.setTimeZone(TimeZone.getTimeZone("UTC"))
          res.setHeader(Names.CONTENT_TYPE, "text/plain; charset=UTF-8")
          res.setHeader(Names.EXPIRES, dateformat.format(new Date))
          res.setHeader(Names.CACHE_CONTROL, "no-cache, must-revalidate")
          res.setHeader(Names.PRAGMA, "no-cache")

          res.setContent(content)
          ctx.getChannel.write(res).addListener(ChannelFutureListener.CLOSE)
        case _ => ctx.sendUpstream(e)
      }
    }
  }

  /**
   * An unfinished implementation of a favico handler.
   * currently always responds with 404.
   *
   * @param favico the file that is the favico. (not used currently)
   */
  class Favico(favico: Option[File] = None) extends SimpleChannelUpstreamHandler {
    override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
      e.getMessage match {
        case r: HttpRequest if r.getUri.toLowerCase.startsWith("/favicon.ico") =>
          val status = HttpResponseStatus.NOT_FOUND
          val response: HttpResponse = new DefaultHttpResponse(HTTP_1_1, status)
          response.setHeader(CONTENT_TYPE, "text/plain; charset=UTF-8")
          response.setContent(ChannelBuffers.copiedBuffer("Failure: "+status.toString+"\r\n", CharsetUtil.UTF_8))
          ctx.getChannel.write(response).addListener(ChannelFutureListener.CLOSE)

        case _ => ctx.sendUpstream(e)
      }
    }
  }

}

/**
 * Netty based HookupServer
 * requires netty 3.4.x or later
 *
 * Usage:
 * {{{
 *   val server = HookupServer(ServerInfo("MyWebSocketServer")) {
 *     new HookupServerClient {
 *       protected val receive = {
 *         case Connected ⇒ println("got a client connection")
 *         case TextMessage(text) ⇒ send(TextMessage("ECHO: " + text))
 *         case Disconnected(_) ⇒ println("client disconnected")
 *       }
 *     }
 *   }
 *   server.start
 *   // time passes......
 *   server.stop
 * }}}
 *
 * @param config A [[io.backchat.hookup.ServerInfo]] to use as configuration for this server
 * @param factory A by-name param that functions as factory for [[io.backchat.hookup.HookupServerClient]]
 */
class HookupServer(val config: ServerInfo, factory: ⇒ HookupServerClient) extends Server {

  protected implicit val executionContext = config.executionContext

  /**
   * The capabilities this server is configured with
   *
   * @return a sequence of [[io.backchat.hookup.ServerCapability]] objects
   */
  def capabilities = config.capabilities

  /**
   * The name of this server
   *
   * @return The name
   */
  def name = config.name

  /**
   * The version of this server.
   *
   * @return The version number of this server, defaults to the version of the build.
   */
  def version = config.version

  /**
   * The network address/host to listen on.
   *
   * @return The address
   */
  def listenOn = config.listenOn

  /**
   * The port this server listens on.
   * @return The port number
   */
  def port = config.port

  import HookupServer._
  import HookupClient.executionContext

  /**
   * the [[org.jboss.netty.logging.InternalLogger]] to use as logger for this server.
   * This logger is shared with the handlers by default so you only ever see a single source of messages
   * The logger has the same name as the server.
   */
  protected val logger = InternalLoggerFactory.getInstance(name)

  private[this] val timer = new HashedWheelTimer()
  private[this] var server: ServerBootstrap = null
  private[this] var serverConnection: Channel = null

  private[this] val allChannels = new DefaultChannelGroup

  /**
   * If you want to override the entire Netty Channel Pipeline that gets created override this method.
   * But you're basically throwing away all the features of this server.
   *
   * @return the created [[org.jboss.netty.channel.ChannelPipeline]]
   */
  protected def getPipeline: ChannelPipeline = {
    val pipe = Channels.pipeline()
    configureFlashPolicySupport(pipe)
    pipe.addLast("connection-tracker", new ConnectionTracker(allChannels))
    addFirstInPipeline(pipe)
    configureSslSupport(pipe)
    addPingSupport(pipe)
    configureHttpSupport(pipe)
    configurePipeline(pipe)
    configureWebSocketSupport(pipe)
    addLastInPipeline(pipe)
    pipe
  }

  private[this] def configureWebSocketSupport(pipe: ChannelPipeline) {
    val raiseEvents = capabilities.contains(RaiseAckEvents)
    val wsClientFactory = new WebSocketClientFactoryHandler(
      logger,
      allChannels,
      factory,
      config.defaultWireFormat,
      subProtocols = Map(config.protocols.map(wf => wf.name -> wf):_*),
      maxFrameSize = config.maxFrameSize,
      raiseEvents = raiseEvents)
    pipe.addLast("websockethandler", wsClientFactory)
    pipe.addLast("websocketoutput", new WebSocketMessageAdapter(logger, config.defaultWireFormat))
    pipe.addLast("acking", new MessageAckingHandler(logger, config.defaultWireFormat, raiseEvents))
    if (raiseEvents) {
      pipe.addLast("eventsHook", new SimpleChannelHandler {
        var theclient: HookupServerClientHandler = null
        override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
          e.getMessage match {
            case ("client", c: HookupServerClientHandler) ⇒ theclient = c
            case m: Ack ⇒ theclient.receive lift m
            case m: AckRequest ⇒ theclient.receive lift m
            case _ ⇒ ctx.sendUpstream(e)
          }
        }
      })
    }
  }

  /**
   * If you want to replace the way http requests are handled and read this is the place to do it.
   *
   * @param pipe The pipeline to configure
   */
  protected def configureHttpSupport(pipe: ChannelPipeline) {
    pipe.addLast("decoder", new HttpRequestDecoder(4096, 8192, 8192))
    pipe.addLast("aggregator", new HttpChunkAggregator(64 * 1024))
    pipe.addLast("encoder", new HttpResponseEncoder)
    config.contentCompression foreach { ctx ⇒
      pipe.addLast("deflater", new HttpContentCompressor(ctx.level))
    }
  }

  private[this] def configureFlashPolicySupport(pipe: ChannelPipeline) {
    // TODO: make this opt-in instead of allow all?
    pipe.addLast("flash-policy", new FlashPolicyHandler(ChannelBuffers.copiedBuffer(config.flashPolicy, CharsetUtil.UTF_8)))
  }

  private[this] def configureSslSupport(pipe: ChannelPipeline) {
    config.sslContext foreach { ctxt ⇒
      val engine = ctxt.createSSLEngine()
      engine.setUseClientMode(false)
      pipe.addLast("ssl", new SslHandler(engine))
    }
  }

  /**
   * If you want to replace the way pings are handled (for example to support sending new lines on a http stream)
   * This is where you can override the handler addition to the pipeline
   */
  protected def addPingSupport(pipe: ChannelPipeline) {
    config.pingTimeout foreach { png =>
      val ping = png.duration.toSeconds.toInt
      if (ping > 0) {
        pipe.addLast("timeouts", new IdleStateHandler(timer, 0, ping, 0))
        pipe.addLast("connection-reaper", new HookupClient.PingPongHandler(logger))
      }
    }
  }

  /**
   * This is the first place where you can add additional handlers to the pipeline.
   * The flashpolicy handler and connection tracker have been added at this point.
   * if a message arrives in this handler it's been untouched.
   * This is a great place to register a handler that deals with metrics like bytes read/transferred etc.
   *
   * @param pipe The pipeline to configure.
   */
  protected def addFirstInPipeline(pipe: ChannelPipeline) {

  }

  /**
   * At this point the pipeline has been configured with flashpolicy, ssl, pinging and connection tracking.
   * HTTP support has also been added to the pipeline. if websocket related messages arrive in this handler
   * it should send them to upstream. And a http request that is a websocket upgrade request should also be
   * sent upstream.
   *
   * @param pipe The pipeline to configure
   */
  protected def configurePipeline(pipe: ChannelPipeline) {

  }

  /**
   * This is the best place to add your application handler.
   * If you want to use a web framework that uses it's own request abstraction, then this is the place to plug it in.
   *
   * @param pipe The pipeline to configure.
   */
  protected def addLastInPipeline(pipe: ChannelPipeline) {

  }

  /**
   * Configure the server bootstrap.
   * This is the place to set socket options.
   * By default it sets soLinger to 0, reuseAddress to true and child.tcpNoDelay to true
   */
  protected def configureBootstrap() {
    server.setOption("soLinger", 0)
    server.setOption("reuseAddress", true)
    server.setOption("child.tcpNoDelay", true)
  }

  private[this] def pipelineFactory = new ChannelPipelineFactory {
    def getPipeline = HookupServer.this.getPipeline
  }

  private[this] val startCallbacks = new ListBuffer[() ⇒ Any]()
  private[this] val stopCallbacks = new ListBuffer[() ⇒ Any]()

  /**
   * Attach blocks of code to be run when the server starts.
   *
   * @param thunk the code to execute when the server starts
   */
  def onStart(thunk: ⇒ Any) = startCallbacks += { () ⇒ thunk }

  /**
   * Attach blocks of code to be run when the server stops
   *
   * @param thunk the code to execute when the server stops
   */
  def onStop(thunk: ⇒ Any) = stopCallbacks += { () ⇒ thunk }

  /**
   * Broadcast a message to '''all''' open connections
   *
   * @param message the [[io.backchat.hookup.OutboundMessage]] to send.
   * @return A future with the result of the operation, a [[io.backchat.hookup.ResultList]]
   */
  def broadcast(message: OutboundMessage) = {
    val lst = allChannels.asScala map (x ⇒ x: BroadcastChannel) map (_ send message)
    Future.sequence(lst) map (l ⇒ ResultList(l.toList))
  }

  private[this] val isStarted = new AtomicBoolean(false)
  /**
   * Start this server
   */
  final def start = {
    server = new ServerBootstrap(new NioServerSocketChannelFactory(Executors.newCachedThreadPool(), Executors.newCachedThreadPool()))
    configureBootstrap()
    server.setPipelineFactory(pipelineFactory)
    val addr = config.listenOn.blankOption.map(l ⇒ new InetSocketAddress(l, port)) | new InetSocketAddress(config.port)
    serverConnection = server.bind(addr)

    sys.addShutdownHook(stop)
    startCallbacks foreach (_.apply())
    isStarted.set(true)
    logger info "Started %s %s on [%s:%d]".format(name, version, listenOn, port)
  }

  /**
   * Stop this server.
   */
  final def stop = {
    if (isStarted.get) {
      allChannels.close().awaitUninterruptibly(5000)
  //    if (serverConnection != null && serverConnection.isBound) serverConnection.unbind().awaitUninterruptibly(2000)
      val thread = new Thread("server-shutdown-thread") {
        override def run = {
          timer.stop.asScala foreach (_.cancel())
          if (server != null) {
            server.releaseExternalResources()
          }
          isStarted.compareAndSet(true, false)
          stopCallbacks foreach (_.apply())
        }
      }
      thread.setDaemon(false)
      thread.start()
  //    thread.join

      logger info "Stopped  %s %s on [%s:%d]".format(name, version, listenOn, port)
    }
  }
}

/**
 * A trait to wrap a server in so it can be used by components that depend on a most basic interfae.
 */
trait Server {

  /**
   * The capabilities this server is configured with
   *
   * @return a sequence of [[io.backchat.hookup.ServerCapability]] objects
   */
  def capabilities: Seq[ServerCapability]

  /**
   * The name of this server
   *
   * @return The name
   */
  def name: String

  /**
   * The version of this server.
   *
   * @return The version number of this server, defaults to the version of the build.
   */
  def version: String

  /**
   * The network address/host to listen on.
   *
   * @return The address
   */
  def listenOn: String

  /**
   * The port this server listens on.
   * @return The port number
   */
  def port: Int

  /**
   * Start this server
   */
  def start

  /**
   * Stop this server
   */
  def stop
}