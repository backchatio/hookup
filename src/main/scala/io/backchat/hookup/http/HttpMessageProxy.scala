package io.backchat.hookup.http

import java.util.{Set => JSet, Map => JMap, List => JList}
import java.lang.{ Iterable => JIterable }
import org.jboss.netty.handler.codec.http.{HttpVersion, HttpMessage}
import org.jboss.netty.buffer.ChannelBuffer

/** Proxy for HttpMessage.  Used by Request and Response. */
trait HttpMessageProxy extends HttpMessage {
  def httpMessage: HttpMessage
  def getHttpMessage(): HttpMessage = httpMessage

  def getHeader(name: String): String                 = httpMessage.headers.get(name)
  def getHeaders(name: String): JList[String]         = httpMessage.headers.getAll(name)
  def getHeaders(): JList[JMap.Entry[String, String]] = httpMessage.headers().entries()
  def containsHeader(name: String): Boolean           = httpMessage.headers.contains(name)
  def getHeaderNames(): JSet[String]                  = httpMessage.headers.names()
  def addHeader(name: String, value: Object)          { httpMessage.headers.add(name, value) }
  def setHeader(name: String, value: Object)          { httpMessage.headers.set(name, value) }
  def setHeader(name: String, values: JIterable[_])   { httpMessage.headers.set(name, values) }
  def removeHeader(name: String)                      { httpMessage.headers.remove(name) }
  def clearHeaders()                                  { httpMessage.headers.clear() }

  def getProtocolVersion(): HttpVersion        = httpMessage.getProtocolVersion()
  def setProtocolVersion(version: HttpVersion) { httpMessage.setProtocolVersion(version) }

  def getContent(): ChannelBuffer        = httpMessage.getContent()
  def setContent(content: ChannelBuffer) { httpMessage.setContent(content) }
  def isChunked: Boolean           = httpMessage.isChunked()
  def setChunked(chunked: Boolean) { httpMessage.setChunked(chunked) }

//  @deprecated("deprecated in netty", "0.2.2")
//  def getContentLength(): Long                   = httpMessage.getContentLength()
//  @deprecated("deprecated in netty", "0.2.2")
//  def getContentLength(defaultValue: Long): Long = httpMessage.getContentLength(defaultValue)
//  @deprecated("deprecated in netty", "0.2.2")
//  def isKeepAlive: Boolean = httpMessage.isKeepAlive()

}
