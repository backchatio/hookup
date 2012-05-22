package io.backchat.hookup.http

import java.util.{Set => JSet, Map => JMap, List => JList}
import java.lang.{ Iterable => JIterable }
import org.jboss.netty.handler.codec.http.{HttpVersion, HttpMessage}
import org.jboss.netty.buffer.ChannelBuffer

/** Proxy for HttpMessage.  Used by Request and Response. */
trait HttpMessageProxy extends HttpMessage {
  def httpMessage: HttpMessage
  def getHttpMessage(): HttpMessage = httpMessage

  def getHeader(name: String): String                 = httpMessage.getHeader(name)
  def getHeaders(name: String): JList[String]         = httpMessage.getHeaders(name)
  def getHeaders(): JList[JMap.Entry[String, String]] = httpMessage.getHeaders()
  def containsHeader(name: String): Boolean           = httpMessage.containsHeader(name)
  def getHeaderNames(): JSet[String]                  = httpMessage.getHeaderNames()
  def addHeader(name: String, value: Object)          { httpMessage.addHeader(name, value) }
  def setHeader(name: String, value: Object)          { httpMessage.setHeader(name, value) }
  def setHeader(name: String, values: JIterable[_])   { httpMessage.setHeader(name, values) }
  def removeHeader(name: String)                      { httpMessage.removeHeader(name) }
  def clearHeaders()                                  { httpMessage.clearHeaders() }

  def getProtocolVersion(): HttpVersion        = httpMessage.getProtocolVersion()
  def setProtocolVersion(version: HttpVersion) { httpMessage.setProtocolVersion(version) }

  def getContent(): ChannelBuffer        = httpMessage.getContent()
  def setContent(content: ChannelBuffer) { httpMessage.setContent(content) }
  def isChunked: Boolean           = httpMessage.isChunked()
  def setChunked(chunked: Boolean) { httpMessage.setChunked(chunked) }

  @deprecated("deprecated in netty", "0.2.2")
  def getContentLength(): Long                   = httpMessage.getContentLength()
  @deprecated("deprecated in netty", "0.2.2")
  def getContentLength(defaultValue: Long): Long = httpMessage.getContentLength(defaultValue)
  @deprecated("deprecated in netty", "0.2.2")
  def isKeepAlive: Boolean = httpMessage.isKeepAlive()

}
