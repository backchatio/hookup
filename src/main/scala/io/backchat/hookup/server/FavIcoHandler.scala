package io.backchat.hookup
package server

import java.io.File
import org.jboss.netty.handler.codec.http.{DefaultHttpResponse, HttpResponse, HttpRequest}
import org.jboss.netty.handler.codec.http.HttpHeaders.Names._
import org.jboss.netty.buffer.ChannelBuffers
import org.jboss.netty.channel.{ChannelFutureListener, MessageEvent, ChannelHandlerContext, SimpleChannelUpstreamHandler}
import http.{Version, Status}

/**
 * An unfinished implementation of a favico handler.
 * currently always responds with 404.
 *
 * @param favico the file that is the favico.
 */
class Favico(favico: Option[File] = None) extends SimpleChannelUpstreamHandler {
  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
    e.getMessage match {
      case r: HttpRequest if r.getUri.toLowerCase.startsWith("/favicon.ico") =>
        if (favico.isDefined && favico.forall(_.exists())) {
          StaticFileHandler.serveFile(ctx, r, favico.get)
        } else {
          val status = Status.NotFound
          val response: HttpResponse = new DefaultHttpResponse(Version.Http11, status)
          response.headers.set(CONTENT_TYPE, "text/plain; charset=UTF-8")
          response.setContent(ChannelBuffers.copiedBuffer("Failure: "+status.toString+"\r\n", Utf8))
          ctx.getChannel.write(response).addListener(ChannelFutureListener.CLOSE)
        }

      case _ => ctx.sendUpstream(e)
    }
  }
}
