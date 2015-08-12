package io.backchat.hookup
package server

import org.jboss.netty.handler.codec.http.{DefaultHttpResponse, HttpRequest}
import org.jboss.netty.handler.codec.http.HttpVersion._
import org.jboss.netty.handler.codec.http.HttpResponseStatus._
import org.jboss.netty.buffer.ChannelBuffers
import org.jboss.netty.handler.codec.http.HttpHeaders.Names
import org.joda.time.DateTime
import org.jboss.netty.channel.{ChannelFutureListener, MessageEvent, ChannelHandlerContext, SimpleChannelUpstreamHandler}

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
        val content = ChannelBuffers.copiedBuffer("pong", Utf8)
        res.headers.set(Names.CONTENT_TYPE, "text/plain; charset=utf-8")
        res.headers.set(Names.EXPIRES, new DateTime().toString(HttpDateFormat))
        res.headers.set(Names.CACHE_CONTROL, "no-cache, must-revalidate")
        res.headers.set(Names.PRAGMA, "no-cache")

        res.setContent(content)
        ctx.getChannel.write(res).addListener(ChannelFutureListener.CLOSE)
      case _ => ctx.sendUpstream(e)
    }
  }
}