package io.backchat.hookup.http

import org.jboss.netty.handler.codec.http.{HttpMessage, HttpResponse, HttpResponseStatus}


/** Proxy for HttpResponse.  Used by Response. */
trait HttpResponseProxy extends HttpResponse with HttpMessageProxy {
  def httpResponse: HttpResponse
  def getHttpResponse(): HttpResponse = httpResponse
  def httpMessage: HttpMessage = httpResponse

  def getStatus(): HttpResponseStatus       = httpResponse.getStatus()
  def setStatus(status: HttpResponseStatus) { httpResponse.setStatus(status) }
}
