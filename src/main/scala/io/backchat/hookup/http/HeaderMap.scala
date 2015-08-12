package io.backchat.hookup
package http

import org.jboss.netty.handler.codec.http.HttpMessage
import scala.collection.JavaConversions._
import collection.{Map, mutable}


/**
 * Adapt headers of an HttpMessage to a mutable Map.  Header names
 * are case-insensitive.  For example, get("accept") is the same as
 * get("Accept").
 */
class HeaderMap(httpMessage: HttpMessage)
  extends mutable.MapLike[String, String, mutable.Map[String, String]] {


  def seq: Map[String, String] = Map.empty ++ iterator

  def get(key: String): Option[String] =
    Option(httpMessage.headers.get(key))

  def getAll(key: String): Iterable[String] =
    httpMessage.headers.getAll(key)

  def iterator: Iterator[(String, String)] =
    httpMessage.headers.entries().toIterator map { entry =>
      (entry.getKey, entry.getValue)
    }

  override def keys: Iterable[String] =
    httpMessage.headers().names()

  override def contains(key: String): Boolean =
    httpMessage.headers.contains(key)

  /** Add a header but don't replace existing header(s). */
  def add(k: String, v: String) = {
    httpMessage.headers.add(k, v)
    this
  }

  /** Add a header and do replace existing header(s). */
  def += (kv: (String, String)) = {
    httpMessage.headers.set(kv._1, kv._2)
    this
  }

  /** Remove header(s). */
  def -= (key: String) = {
    httpMessage.headers.remove(key)
    this
  }

  def empty = mutable.Map[String, String]()
}
