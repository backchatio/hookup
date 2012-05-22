package io.backchat.hookup
package http

import scala.collection.JavaConversions._
import org.jboss.netty.handler.codec.base64.Base64
import org.jboss.netty.buffer.ChannelBuffers

object ProxyCredentials {
  def apply(credentials: java.util.Map[String, String]): Option[ProxyCredentials] =
    apply(credentials.toMap)

  def apply(credentials: Map[String, String]): Option[ProxyCredentials] = {
    for {
      user <- credentials.get("http_proxy_user")
      pass <- credentials.get("http_proxy_pass")
    } yield {
      ProxyCredentials(user, pass)
    }
  }
}

case class ProxyCredentials(username: String, password: String) {
  lazy val basicAuthorization = {
    val bytes = "%s:%s".format(username, password).getBytes
    "Basic " + Base64.decode(ChannelBuffers.wrappedBuffer(bytes)).toString(Utf8)
  }
}
