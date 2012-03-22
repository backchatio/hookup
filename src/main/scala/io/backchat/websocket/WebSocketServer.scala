package io.backchat.websocket

import org.jboss.netty.bootstrap.ServerBootstrap
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory
import java.util.concurrent.{ TimeUnit, Executors }
import java.net.{ InetSocketAddress }
import org.jboss.netty.channel._
import group.{ChannelGroup, DefaultChannelGroup}
import org.jboss.netty.handler.codec.http.{HttpRequest, HttpChunkAggregator, HttpRequestDecoder, HttpResponseEncoder}
import org.jboss.netty.handler.codec.http.websocketx._
import org.jboss.netty.handler.codec.http.HttpHeaders.Values
import org.jboss.netty.handler.codec.http.HttpHeaders.Names
import java.util.Locale.ENGLISH
import org.jboss.netty.buffer.ChannelBuffers
import org.jboss.netty.logging.{InternalLogger, InternalLoggerFactory}

trait WebSocketServerConfig {
  def listenOn: String
  def port: Int
}


/**
 * Netty based WebSocketServer
 * requires netty 3.3.x or later
 *
 * Usage:
 * <pre>
 *   val conf = new WebSocketServerConfig {
 *     val port = 14567
 *     val listenOn = "0.0.0.0"
 *   }
 *
 *   val server = WebSocketServer(conf) {
 *     case Connect(_) => println("got a client connection")
 *     case TextMessage(cl, text) => cl.write(TextMessage("ECHO: " + text))
 *     case Disconnected(_) => println("client disconnected")
 *   }
 *   server.start
 *   // time passes......
 *   server.stop
 * </pre>
 */
object WebSocketServer {
  
  type WebSocketHandler = PartialFunction[WebSocketMessage, Unit]
  
  sealed trait WebSocketMessage
  case class Connect(client: Channel) extends WebSocketMessage
  case class TextMessage(client: Channel, content: String) extends WebSocketMessage
  case class BinaryMessage(client: Channel, content: Array[Byte]) extends WebSocketMessage
  case class Error(client: Channel, cause: Option[Throwable]) extends WebSocketMessage
  case class Disconnected(client: Channel) extends WebSocketMessage


  def apply(config: WebSocketServerConfig)(handler: WebSocketServer.WebSocketHandler): WebSocketServer =
    new WebSocketServer(config, handler)

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

  private class WebSocketPartialFunctionHandler(handler: WebSocketHandler, logger: InternalLogger) extends SimpleChannelUpstreamHandler {

    private[this] var collectedFrames: Seq[ContinuationWebSocketFrame] = Vector.empty[ContinuationWebSocketFrame]

    private[this] var handshaker: WebSocketServerHandshaker = _

    override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
      e.getMessage match {
        case httpRequest: HttpRequest if isWebSocketUpgrade(httpRequest) ⇒ handleUpgrade(ctx, httpRequest)
        case m: TextWebSocketFrame => handler lift TextMessage(e.getChannel, m.getText)
        case m: BinaryWebSocketFrame => handler lift BinaryMessage(e.getChannel, m.getBinaryData.array)
        case m: ContinuationWebSocketFrame => {
          if (m.isFinalFragment) {
            handler lift TextMessage(e.getChannel, collectedFrames map (_.getText) reduce (_ + _))
            collectedFrames = Nil
          } else {
            collectedFrames :+= m
          }
        }
        case f: CloseWebSocketFrame ⇒
          if (handshaker != null) handshaker.close(ctx.getChannel, f)
          handler lift Disconnected(e.getChannel)
        case _: PingWebSocketFrame ⇒ e.getChannel.write(new PongWebSocketFrame)
        case _ ⇒ ctx.sendUpstream(e)
      }
    }

    override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
      handler lift Error(e.getChannel, Option(e.getCause))
    }

    private def isWebSocketUpgrade(httpRequest: HttpRequest): Boolean = {
      val connHdr = httpRequest.getHeader(Names.CONNECTION)
      val upgrHdr = httpRequest.getHeader(Names.UPGRADE)
      (connHdr != null && connHdr.equalsIgnoreCase(Values.UPGRADE)) &&
        (upgrHdr != null && upgrHdr.equalsIgnoreCase(Values.WEBSOCKET))
    }

    private def handleUpgrade(ctx: ChannelHandlerContext, httpRequest: HttpRequest) {
      val handshakerFactory = new WebSocketServerHandshakerFactory(websocketLocation(httpRequest), null, false)
      handshaker = handshakerFactory.newHandshaker(httpRequest)
      if (handshaker == null) handshakerFactory.sendUnsupportedWebSocketVersionResponse(ctx.getChannel)
      else {
        handshaker.handshake(ctx.getChannel, httpRequest)
        handler.lift(Connect(ctx.getChannel))
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
        case TextMessage(_, text) => ctx.getChannel.write(new TextWebSocketFrame(text))
        case BinaryMessage(_, bytes) => ctx.getChannel.write(new BinaryWebSocketFrame(ChannelBuffers.copiedBuffer(bytes)))
      }
    }
  }

}

class WebSocketServer(val config: WebSocketServerConfig, val handler: WebSocketServer.WebSocketHandler) {

  import WebSocketServer._
  private[this] val realHandler = handler orElse devNull
  private[this] val devNull: WebSocketHandler = {
    case Error(_, Some(ex)) =>
      System.err.println(ex.getMessage)
      ex.printStackTrace()
    case _ =>
  }
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
    pipe.addLast("decoder", new HttpRequestDecoder(4096, 8192, 8192))
    pipe.addLast("aggregator", new HttpChunkAggregator(64 * 1024))
    pipe.addLast("encoder", new HttpResponseEncoder)
    pipe.addLast("websockethandler", new WebSocketPartialFunctionHandler(realHandler, logger))
    pipe.addLast("websocketoutput", new WebSocketMessageAdapter(logger))
    pipe
  }

  private[this] val servName = getClass.getSimpleName

  def start = synchronized {
    server.setPipeline(getPipeline)
    val addr = if (config.listenOn == null || config.listenOn.trim.isEmpty) new InetSocketAddress(config.port)
    else new InetSocketAddress(config.listenOn, config.port)
    val sc = server.bind(addr)
    allChannels add sc
    logger info "Started %s on [%s:%d]".format(servName, config.listenOn, config.port)
  }

  def stop = synchronized {
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
    logger info "Stopped %s".format(servName)
  }
}