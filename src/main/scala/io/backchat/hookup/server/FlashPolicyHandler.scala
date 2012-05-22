package io.backchat.hookup
package server

import org.jboss.netty.handler.codec.frame.FrameDecoder
import org.jboss.netty.channel.{ChannelPipeline, ChannelFutureListener, Channel, ChannelHandlerContext}
import org.jboss.netty.buffer.{ChannelBuffers, ChannelBuffer}

object FlashPolicyHandler {
  val PolicyXml = <cross-domain-policy><allow-access-from domain="*" to-ports="*"/></cross-domain-policy>
  val AllowAllPolicy = ChannelBuffers.copiedBuffer(PolicyXml.toString(), Utf8)
}

  /**
   * A flash policy handler for netty. This needs to be included in the pipeline before anything else has touched
   * the message.
   *
   * @see [[https://github.com/cgbystrom/netty-tools/blob/master/src/main/java/se/cgbystrom/netty/FlashPolicyHandler.java]]
   * @param policyResponse The response xml to send for a request
   */
  class FlashPolicyHandler(policyResponse: ChannelBuffer = FlashPolicyHandler.AllowAllPolicy) extends FrameDecoder {

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