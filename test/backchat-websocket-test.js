
var vows = require("vows"),
    assert = require("assert"),
    WebSocket = require("../lib/backchat-websocket").WebSocket;

vows.describe("BackChat WebSocket").addBatch({

  "when initializing": {
    topic: {
      uri: "ws://localhost:2949/",
      retries: [1, 2, 3, 4, 5],
      buffered: true,
      defaultsClient: new WebSocket("ws://localhost:2949/"),
      configuredClient: new WebSocket({uri: "ws://localhost:2949/", reconnectSchedule: {min:1, max: 5}, buffered: true})},
    'fails when the uri param is': {
      "missing": function (topic) {
        assert.throws(function () { new WebSocket() }, Error);
      },
      "an invalid uri": function (topic) {
        assert.throws(function () { new WebSocket({uri: "http:"}) }, Error);
      }
    },
    "should use the default retry schedule": function (topic) {
      assert.equal(topic.defaultsClient.retrySchedule, WebSocket.RECONNECT_SCHEDULE);
    },
    "should set journaling to false by default": function (topic) {
      assert.isFalse(topic.defaultsClient.isBuffered());
    },
    "should use the provided uri": function (topic) {
      assert.equal(topic.defaultsClient.uri, topic.uri);
    },
    "should use the retry schedule from the options": function (topic) {
      assert.deepEqual(topic.configuredClient.retrySchedule, topic.retries);
    },
    "should use the journaling value from the options": function (topic) {
      assert.isTrue(topic.configuredClient.isBuffered());
    }
  },

  "when sending json to the server": {
    
  }

}).export(module);