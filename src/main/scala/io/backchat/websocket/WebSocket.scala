package io.backchat.websocket

import org.jboss.netty.buffer.ChannelBuffer
import com.twitter.concurrent.{Observer, Offer}
import org.jboss.netty.handler.codec.http.websocketx.WebSocketFrame
import com.twitter.finagle.{Codec, CodecFactory}
import org.jboss.netty.handler.codec.http.{HttpRequestDecoder, HttpChunkAggregator, HttpResponseEncoder}
import org.jboss.netty.channel.{Channels, ChannelPipelineFactory}


trait WebSocketResponse {
  def messages: Offer[ChannelBuffer]
  def error: Offer[Throwable]
}
class WebSocket extends CodecFactory[WebSocketFrame, WebSocketFrame] {
  def client = Function.const _

  def server = Function.const {
    new Codec[WebSocketFrame, WebSocketFrame] {
      def pipelineFactory = new ChannelPipelineFactory {
        def getPipeline = {
          val pipe = Channels.pipeline()
          pipe.addLast("decoder", new HttpRequestDecoder(4096, 8192, 8192))
          pipe.addLast("aggregator", new HttpChunkAggregator(64 * 1024))
          pipe.addLast("encoder", new HttpResponseEncoder)
          pipe
        }
      }
    }
  }
}
