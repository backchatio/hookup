package io.backchat.hookup
package tests

import org.specs2.Specification
import net.liftweb.json._
import JsonDSL._
import scala.concurrent.duration._
import org.specs2.time.NoTimeConversions

class JsonProtocolWireFormatSpec extends Specification with NoTimeConversions { def is =
  "A JsonProtocolWireFormat should" ^
    testWireFormat("text", textMessage, text, text) ^
    testWireFormat("json", jsonMessage, json, json) ^
    testWireFormat("json array", jsonArrayMessage, jsonArray, jsonArray) ^
    testWireFormat("ack", ackMessage, ack, ack) ^
    "parse an ack request message" ! { wf.parseInMessage(ackRequestMessage) must_== ackRequest } ^
    "build a needs ack message" ! { wf.parseOutMessage(needsAckMessage) must_== needsAck } ^
    "render a needs ack message" ! { wf.render(needsAck) must_== needsAckMessage }
  end

  val wf = new JsonProtocolWireFormat()(DefaultFormats)

  val textMessage = """{"type":"text","content":"this is a text message"}"""
  val text = TextMessage("this is a text message")

  val jsonMessage = """{"type":"json","content":{"data":"a json message"}}"""
  val json = JsonMessage(("data" -> "a json message"))

  val jsonArrayMessage = """{"type":"json","content":["data","a json message"]}"""
  val jsonArray = JsonMessage(List("data", "a json message"))

  val ackMessage = """{"type":"ack","id":3}"""
  val ack = Ack(3L)

  val ackRequestMessage = """{"type":"ack_request","id":3,"content":"this is a text message"}"""
  val ackRequest = AckRequest(text, 3)

  val needsAckMessage = """{"type":"needs_ack","timeout":5000,"content":{"type":"text","content":"this is a text message"}}"""
  val needsAck: OutboundMessage = NeedsAck(text, 5.seconds)

  def testWireFormat(name: String, serialized: String, in: InboundMessage, out: OutboundMessage) =
    "parse a %s message".format(name) ! { wf.parseInMessage(serialized) must_== in } ^
    "build a %s message".format(name) ! { wf.parseOutMessage(serialized) must_== out } ^
    "render a %s message".format(name) ! { wf.render(out) must_== serialized }

}
