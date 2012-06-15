package io.backchat.hookup

import akka.util.Duration
import akka.util.duration._

/**
 * The interface for an immutable backoff schedule
 */
trait Throttle {
  /**
   * The delay to wait until the next operation can occur
   *
   * @return A [[akka.util.Duration]]
   */
  def delay: Duration

  /**
   * The maximum value the delay can have
   *
   * @return A [[akka.util.Duration]]
   */
  def maxWait: Duration

  /**
   * Calculate the next delay and return a new throttle
   * @return A new [[io.backchat.hookup.Throttle]]
   */
  def next(): Throttle
}

/**
 * A Null object representing no backing off at all
 */
case object NoThrottle extends Throttle {

  /**
   * The delay is always 0
   */
  val delay = 0.millis

  /**
   * The max wait is always 0
   */
  val maxWait = 0.millis

  /**
   * Always returns a [[io.backchat.hookup.NoThrottle]]
   * @return A [[io.backchat.hookup.NoThrottle]]
   */
  def next(): Throttle = NoThrottle
}

/**
 * Represents a back off strategy that will retry forever when the maximum wait has been reached
 * From then on it will continue to use the max wait as delay.
 *
 * @param delay An [[akka.util.Duration]] indicating how long to wait for the next operation can occur
 * @param maxWait An [[akka.util.Duration]] indicating the maximum value a `delay` can have
 */
case class IndefiniteThrottle(delay: Duration, maxWait: Duration) extends Throttle {

  def next(): Throttle = {
    copy(delay = delay.doubled min maxWait)
  }
}

/**
 * Represents a back off strategy that will retry for `maxTimes` when the maximum wait has been reached
 * When it can't connect within the `maxTimes` a `maxValue` can occur it will return a [[io.backchat.hookup.NoThrottle]] strategy
 *
 * @param delay An [[akka.util.Duration]] indicating how long to wait for the next operation can occur
 * @param maxWait An [[akka.util.Duration]] indicating the maximum value a `delay` can have
 * @param maxTimes A [[scala.Int]] representing the maximum amount of time a maxWait can be repeated
 */
case class MaxTimesThrottle(delay: Duration, maxWait: Duration, maxTimes: Int = 1) extends Throttle {

  def next(): Throttle = {
    if (maxTimes > 0)
      copy(delay = delay.doubled min maxWait, maxTimes = maxTimes - 1)
    else NoThrottle
  }
}