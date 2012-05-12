var vows = require("vows"),
    assert = require("assert"),
    WireFormat = require("../lib/wireformat");

vows.describe("WireFormat").addBatch({
  "A WireFormat": {
    topic: {
      text: "this is a text message",
      textResult: { type: "text", content: "this is a text message" },
      jsonResult: { content: { data: "a json message" }, type: 'json'},
      jsonData: {data: "a json message"},
      json: JSON.stringify({data: "a json message"}),
      arrayResult: { content: [1, 2, 3, 4], type: "json"},
      arrayData: [1, 2, 3, 4],
      arrayJson: JSON.stringify([1, 2, 3, 4]),
      ackResult: { id: 3, type: "ack" },
      ack: JSON.stringify({ id: 3, type: "ack" }),
      ackRequestResult: { id: 3, type: "ack_request", content:  { type: "text", content: "this is a text message" }},
      ackRequestData: { id: 3, type: "ack_request", content:  "this is a text message" },
      ackRequest: JSON.stringify({ id: 3, type: "ack_request", content: { type: "text", content: "this is a text message" }}),
      needsAckResult: { type: "needs_ack", timeout: 5000, content: {type: "text", content: "this is a text message"} },
      needsAck: JSON.stringify({ type: "needs_ack", timeout: 5000, content: {type: "text", content: "this is a text message"} }),
      wireFormat: new(WireFormat)
    },
    "parses messages from": {
      "a text message": function(topic) {
        assert.deepEqual(topic.wireFormat.parseMessage(topic.text), topic.textResult);
      },
      "a json message": function(topic) {
        assert.deepEqual(topic.wireFormat.parseMessage(topic.json), topic.jsonResult);
      },
      "an array json message": function(topic) {
        assert.deepEqual(topic.wireFormat.parseMessage(topic.arrayJson), topic.arrayResult);
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
      "an array json message": function(topic) {
        assert.deepEqual(topic.wireFormat.buildMessage(topic.arrayData), topic.arrayResult);
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
      "an array json message": function(topic) {
        assert.deepEqual(topic.wireFormat.unwrapContent(topic.arrayResult), topic.arrayData);
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