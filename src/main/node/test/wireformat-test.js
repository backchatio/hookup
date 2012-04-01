var vows = require("vows"),
    assert = require("assert"),
    WireFormat = require("../lib/wireformat").WireFormat;

vows.describe("WireFormat").addBatch({
  "A WireFormat": {
    topic: {
      text: "this is a text message",
      textResult: { type: "text", content: "this is a text message" },
      jsonResult: {data: "a json message", type: 'json'},
      jsonData: {data: "a json message"},
      json: JSON.stringify({data: "a json message"}),
      ackResult: { id: 3, type: "ack" },
      ack: JSON.stringify({ id: 3, type: "ack" }),
      ackRequestResult: { id: 3, type: "ack_request", content:  { type: "text", content: "this is a text message" }},
      ackRequestData: { id: 3, type: "ack_request", content:  "this is a text message" },
      ackRequest: JSON.stringify({ id: 3, type: "ack_request", content: { type: "text", content: "this is a text message" }}),
      needsAckResult: { type: "needs_ack", timeout: 5000 },
      needsAck: JSON.stringify({ type: "needs_ack", timeout: 5000 }),
      wireFormat: new(WireFormat)
    },
    "parses messages from": {
      "a text message": function(topic) {
        assert.deepEqual(topic.wireFormat.parseMessage(topic.text), topic.textResult);
      },
      "a json message": function(topic) {
        assert.deepEqual(topic.wireFormat.parseMessage(topic.json), topic.jsonResult);
      },
      "an ack message": function(topic) {
        assert.deepEqual(topic.wireFormat.parseMessage(topic.ack), topic.ackResult);
      },
      "an ack_request message": function(topic) {
        assert.deepEqual(topic.wireFormat.parseMessage(topic.ackRequest), topic.ackRequestResult);
      },
      "a needs_ack message": function(topic) {
        assert.deepEqual(topic.wireFormat.parseMessage(topic.needsAck), topic.needsAckResult);
      } 
    },

    "builds messages": {
      "a text message": function(topic) {
        assert.deepEqual(topic.wireFormat.buildMessage(topic.text), topic.textResult);
      },
      "a json message": function(topic) {
        assert.deepEqual(topic.wireFormat.buildMessage(topic.jsonData), topic.jsonResult);
      },
      "an ack message": function(topic) {
        assert.deepEqual(topic.wireFormat.buildMessage(topic.ackResult), topic.ackResult);
      },
      "an ack_request message": function(topic) {
        assert.deepEqual(topic.wireFormat.buildMessage(topic.ackRequestData), topic.ackRequestResult);
      }
    },

    "unwraps messages": {
      "a text message": function(topic) {
        assert.deepEqual(topic.wireFormat.unwrapContent(topic.textResult), topic.text);
      },
      "a json message": function(topic) {
        assert.deepEqual(topic.wireFormat.unwrapContent(topic.jsonResult), topic.jsonData);
      },
      "an ack message": function(topic) {
        assert.deepEqual(topic.wireFormat.unwrapContent(topic.ackResult), topic.ackResult);
      },
      "an ack_request message": function(topic) {
        assert.deepEqual(topic.wireFormat.unwrapContent(topic.ackRequestResult), topic.text);
      },
      "a needs_ack message": function(topic) {
        assert.deepEqual(topic.wireFormat.unwrapContent(topic.needsAckResult), topic.needsAckResult);
      } 
    }
  }
}).export(module);