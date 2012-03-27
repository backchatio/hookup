package io.backchat.websocket

sealed trait OperationResult
case object Success extends OperationResult
case object Cancelled extends OperationResult
case class ResultList(results: List[OperationResult]) extends OperationResult
