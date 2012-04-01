
var vows = require("vows"),
    assert = require("assert"),
    WebSocket = require("../lib/backchat-websocket").WebSocket,
    WebSocketClient = require('faye-websocket'),
    WireFormat = require("../lib/wireformat").WireFormat,
    _ = require('underscore'),
    fs = require('fs'),
    http = require('http'),
    util = require('util'),
    events = require('events');

var EchoServer = function() {
  var self = this;
  var wf = new WireFormat();
  var appHandler = function(request, socket,  head) {
    var ws = new WebSocketClient(request, socket, head);

    ws.onmessage = function(event) {
      var parsed = wf.parseMessage(event.data);
      console.log(util.inspect(parsed));
      if (parsed.type === "ack_request" && parsed.id == 1) {
        ws.send(wf.renderMessage({type: "ack", id: 1}));
        ws.send(wf.renderMessage(parsed.content));
      } else if (parsed.type !== "ack_request") {
        ws.send(event.data);
      }
    };

    ws.onclose = function(event) {
      ws = null;
      self.close();
    };
  }
  var staticHandler = function(request, response) {
    var path = request.url;
    
    fs.readFile(__dirname + path, function(err, content) {
      var status = err ? 404 : 200;
      response.writeHead(status, {'Content-Type': 'text/html'});
      response.write(content || 'Not found');
      response.end();
    });
  };
  this._server = http.createServer();
  this._server.addListener('upgrade', appHandler);
  this._server.addListener('request', staticHandler);
  this._server.on('error', function(err) { self.emit('error', err) });
}

util.inherits(EchoServer, events.EventEmitter);

_.extend(EchoServer.prototype, {
  listen: function(port) {
    var self = this;
    this._server.listen(port, function(err) {
      self.emit('listen');
    });
  },
  close: function() {
    var self = this;
    if (this._server) {
      this._server.once('close', function() {
        self.emit('close');
      });
      this._server.close();
    }
  }
});


vows.describe("BackChat WebSocket").addBatch({

  "A BackChat WebSocket, ": {
    topic: {},
    "when initializing, ": {
      topic: {
        uri: "ws://localhost:2949/",
        retries:  {min:1, max: 5},
        buffered: true,
        defaultsClient: new WebSocket("ws://localhost:2949/"),
        configuredClient: new WebSocket({uri: "ws://localhost:2949/", reconnectSchedule: { min: 1, max: 5 }, buffered: true})},
      'fails when the uri param is': {
        "missing": function (topic) {
          assert.throws(function () { new WebSocket() }, Error);
        },
        "an invalid uri": function (topic) {
          assert.throws(function () { new WebSocket({uri: "http:"}) }, Error);
        }
      },
      "should use the default retry schedule": function (topic) {
        assert.equal(topic.defaultsClient.reconnectSchedule, WebSocket.RECONNECT_SCHEDULE);
      },
      "should set journaling to false by default": function (topic) {
        assert.isFalse(topic.defaultsClient.isBuffered());
      },
      "should use the provided uri": function (topic) {
        assert.equal(topic.defaultsClient.uri, topic.uri);
      },
      "should use the retry schedule from the options": function (topic) {
        assert.deepEqual(topic.configuredClient.reconnectSchedule, topic.retries);
      },
      "should use the journaling value from the options": function (topic) {
        assert.isTrue(topic.configuredClient.isBuffered());
      }
    },

    "when sending json to the server": {
      topic: function(options) {
        var port = 2951;
        var promise = new events.EventEmitter();
        var server = new EchoServer();
        var closed=0, reconnecting=0;
        var messages = [];
        server.on('listen', function() {
          var ws = new WebSocket({uri: "ws://localhost:"+port+"/"});
          ws.on('close', function() {
            closed++;
            try { server.close() } catch (e) { };
          });
          ws.on('data', function(evt) {
            messages.push(evt);
            ws.close();
          });
          ws.on('reconnecting', function() {
            reconnecting++;
          });
          ws.on('connected', function() {
            ws.send({data: "the message"});
          });
          ws.connect();
        });
        server.on('close', function() {
          promise.emit('success', {closed: closed, reconnecting: reconnecting, messages: messages});
        });
        server.listen(port);
        return promise;
      },
      "the client can send and receive messages from the server": function(topic) {
        assert.deepEqual(topic.messages[0], {data: "the message"});
      } 
    }
  }}).addBatch({
    "when expecting acks": {
      topic: function(options) {
        var port = 2952;
        var promise = new events.EventEmitter();
        var server = new EchoServer();
        var ackRequests=0, acks=0, failedAcks = 0;
        var killSwitch = null;
        server.on('listen', function() {
          var ws = new WebSocket({uri: "ws://localhost:"+port+"/", raiseAckEvents: true});
          ws.on('data', function(data) {
            console.log("data");
            console.log(data);
          })
          ws.on('ack_failed', function() {
            console.log("ack failed");
            failedAcks++;
            if (killSwitch) clearTimeout(killSwitch);
            if (ws) ws.close();
          });
          ws.on('close', function() {
            try { server.close() } catch (e) { };
          });
          ws.on('ack', function(data) { 
            console.log("ack");
            console.log(util.inspect(data));
            acks++ ;
          });
          ws.on('ack_request', function(data) { 
            console.log("ack_request");
            console.log(util.inspect(data));
            ackRequests++ 
          });
          ws.on('connected', function() {
            ws.sendAcked({ data: "the first message"});
            ws.sendAcked({ data: "the second message"}, { timeout: 500});
            killSwitch = setTimeout(function() { if (ws && ws.isConnected()) ws.close() }, 5000);
          });
          ws.connect();
        });
        server.on('close', function() {
          promise.emit('success', {ackRequests: ackRequests, acks: acks, failedAcks: failedAcks});
        });
        server.on('error',function(err) { promise.emit('error', err)});
        server.listen(port);
        return promise;
      },
      "creates an ack request": function(topic) {
        assert.equal(topic.ackRequests, 2);
      },
      "a message gets acked by the server": function(topic) {
        assert.equal(topic.acks, 1);
      },
      "notifies of failed ack": function(topic) {
        assert.equal(topic.failedAcks, 1);
      }
    }    
  }).addBatch({
    "when reconnecting": {
      topic: function() {
        var port = 2951;
        var promise = new events.EventEmitter();
        var server = new EchoServer();
        var closed=0, reconnecting=0;
        server.on('listen', function() {
          var ws = new WebSocket({uri: "ws://localhost:"+port+"/"});
          ws.on('close', function() {
            try { server.close() } catch (e) { };
          });
          ws.on('reconnecting', function() {
            reconnecting++;
          });
          ws.on('connected', function() {
            if (closed === 0) server.close();
          });
          ws.connect();
        });
        server.on('close', function() {
          if (closed > 0) promise.emit('success', reconnecting);
          else {
            closed++;
            process.nextTick(function (){server.listen(port)});
          }
        });
        server.listen(port);
        return promise;
      },
      "reconnects to the server": function(topic) {
        assert.ok(topic > 0)
      }
    }
  }).addBatch({

    "when closing the connection": {
      topic: function(options) {
        var promise = new events.EventEmitter();
        var server = new EchoServer();
        var closed=0, reconnecting=0;
        server.on('listen', function() {
          var ws = new WebSocket({uri: "ws://localhost:2950/"});
          ws.on('close', function() {
            closed++;
            try { server.close() } catch (e) { };
          });
          ws.on('reconnecting', function() {
            reconnecting++;
          });
          ws.on('connected', function() {
            ws.close();
          });
          ws.connect();
        });
        server.on('close', function() {
          promise.emit('success', {closed: closed, reconnecting: reconnecting})
        });
        server.listen(2950);
        return promise;
      },
      "the client disconnects": function(topic) {
        assert.equal(topic.closed, 1);
      },
      "the client does not attempt to reconnect": function(topic) {
        assert.equal(topic.reconnecting, 0);
      }
    }

}).export(module);