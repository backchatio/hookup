package io.backchat

import websocket.WebSocketServer.BroadcastChannel


package object websocket {

  private[websocket] implicit def string2richerString(s: String) = new {
    def blankOption = if (isBlank) None else Some(s)
    def isBlank = s == null || s.trim.isEmpty
    def nonBlank = !isBlank
  }

  private[websocket] implicit def option2richerOption[T](opt: Option[T]) = new {
    def `|`(other: => T): T = opt getOrElse other
  }

  implicit def fn2BroadcastFilter(fn: WebSocketServer.BroadcastChannel => Boolean): WebSocketServer.BroadcastFilter = {
    new WebSocketServer.BroadcastFilter {
      def apply(v1: BroadcastChannel) = fn(v1)
    }
  }
}