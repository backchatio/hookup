package io.backchat.websocket

import akka.util.Duration
import akka.util.duration._

trait Throttle {
  def delay: Duration
  def maxWait: Duration
  def next(): Throttle
}
case object NoThrottle extends Throttle {

  val delay = 0.millis
  val maxWait = 0.millis

  def next(): Throttle = NoThrottle
}
case class IndefiniteThrottle(delay: Duration, maxWait: Duration) extends Throttle {

  def next(): Throttle = {
    copy(delay = delay.doubled min maxWait)
  }
}

case class MaxTimesThrottle(delay: Duration, maxWait: Duration, maxTimes: Int = 1) extends Throttle {

  def next(): Throttle = {
    if (maxTimes > 0)
      copy(delay = delay.doubled min maxWait, maxTimes = maxTimes - 1)
    else NoThrottle
  }
}