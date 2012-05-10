package io.backchat.websocket

import reflect.BeanProperty
import collection.JavaConverters._

/**
 * The interface to represent a result from an operation
 * These results can indicate Success, Cancellation or a sequence of operation results
 */
sealed trait OperationResult {
  /**
   * Flag for the java api to indicate success
   * @return A boolean indicating success
   */
  @BeanProperty
  def isSuccess: Boolean = false

  /**
   * Flag for the java api to indicate cancellation
   * @return A boolean indication cancellation
   */
  @BeanProperty
  def isCancelled: Boolean = false

  /**
   * A list of child operation results.
   * @return
   */
  @BeanProperty
  def children: java.util.List[OperationResult] = List[OperationResult]().asJava
}

/**
 * An object indicating a success result
 */
case object Success extends OperationResult {

  /**
  * Flag for the java api to indicate success
  * @return A boolean indicating success, always returns true here
  */
  @BeanProperty
  override def isSuccess: Boolean = true
}

/**
 * An object indicating a failure result
 */
case object Cancelled extends OperationResult {

  /**
   * Flag for the java api to indicate cancellation
   * @return A boolean indication cancellation, always returns true here
   */
  @BeanProperty
  override def isCancelled: Boolean = true
}

/**
 * A list of operation results, contains some aggregate helper methods in addition to a populated list of children
 * @param results a [[scala.List]] of [[io.backchat.websocket.OperationResult]] objects
 */
case class ResultList(results: List[OperationResult]) extends OperationResult {

  /**
   * Flag for the java api to indicate success, returns true when the list is empty or all the
   * elements in the list are [[io.backchat.websocket.Success]] objects.
   * @return A boolean indicating success
   */
  @BeanProperty
  override def isSuccess = results.forall(_.isSuccess)

  /**
   * Flag for the java api to indicate cancellation, returns true when the list is not empty and at least
   * one of the elements in the list is a [[io.backchat.websocket.Cancelled]] object.
   * @return A boolean indicating cancellation
   */
  @BeanProperty
  override def isCancelled = results.nonEmpty && results.exists(_.isCancelled)

  /**
   * A list of child operation results.
   * @return a [[java.util.List]] of [[io.backchat.websocket.OperationResult]] objects
   */
  @BeanProperty
  override def children: java.util.List[OperationResult] = results.asJava

  /**
   * A list of child cancellation results.
   * @return a [[java.util.List]] of [[io.backchat.websocket.Cancelled]] objects
   */
  @BeanProperty
  def cancellations: java.util.List[OperationResult] = (results filter (_.isCancelled)).asJava

  /**
   * A list of child success results.
   * @return a [[java.util.List]] of [[io.backchat.websocket.Success]] objects
   */
  @BeanProperty
  def sucesses: java.util.List[OperationResult] = (results filter (_.isSuccess)).asJava
}
