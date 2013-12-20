package io.backchat.hookup.server

import org.jboss.netty.channel.{ChannelFutureListener, MessageEvent, ChannelHandlerContext, SimpleChannelUpstreamHandler}
import org.jboss.netty.handler.codec.http.{DefaultHttpResponse, HttpResponse, HttpResponseStatus, HttpRequest}
import org.jboss.netty.handler.codec.http.HttpVersion._
import org.jboss.netty.handler.codec.http.HttpHeaders.Names._
import org.jboss.netty.buffer.ChannelBuffers
import org.jboss.netty.util.CharsetUtil

class DropUnhandledRequests extends SimpleChannelUpstreamHandler {

  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) = e.getMessage match {
    case p: HttpRequest ⇒ sendError(ctx, HttpResponseStatus.NOT_FOUND)
    case _              ⇒ ctx.sendUpstream(e)
  }

  protected def sendError(ctx: ChannelHandlerContext, status: HttpResponseStatus) {
    val response: HttpResponse = new DefaultHttpResponse(HTTP_1_1, status)
    response.setHeader(CONTENT_TYPE, "text/plain; charset=UTF-8")
    response.setContent(ChannelBuffers.copiedBuffer("Failure: "+status.toString+"\r\n", CharsetUtil.UTF_8))
    ctx.getChannel.write(response).addListener(ChannelFutureListener.CLOSE)
  }
}