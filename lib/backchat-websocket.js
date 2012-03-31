var WebSocket = require('faye-websocket'),
    _ = require('underscore'),
    util = require('util'),
    events = require('events'),
    FileBuffer = require("./filebuffer").FileBuffer,
    WireFormat = require("./wireformat").WireFormat,
    fs = require('fs'),
    Uri = require('url');


var RECONNECT_SCHEDULE = [1, 1, 1, 1, 1, 5, 5, 5, 5, 5, 10, 10, 10, 10, 10, 30, 30, 30, 30, 30, 60, 60, 60, 60, 60, 300, 300, 300, 300, 300];
var BUFFER_PATH = './logs/buffer.log';
var EVENT_NAMES = {
  connected: "open",
  receive: "message",
  disconnected: "close"
}

DISCONNECTED=0
DISCONNECTING=1
RECONNECTING=2
CONNECTING=3
CONNECTED=4


var ServerClient = exports.WebSocket = function (options) {
  events.EventEmitter.call(this);
  if (typeof options === 'string') {
    options = { uri: options }
  }
  if (!options['uri'] || options['uri'].trim().length == 0) throw new Error('`uri` option is required.');
  this.uri = options['uri'];
  this._uri = Uri.parse(this.uri.replace(/^http/i, 'ws'));
  if (!this._uri.host || !((this._uri.protocol||"").match(/^ws/))) throw new Error("Invalid uri supplied.")
  delete options['uri'];
  
  this.retrySchedule = options.retrySchedule || RECONNECT_SCHEDULE;
  this.retryIndefinitely = !!options.retryIndefinitely;

  this._quiet = options['quiet']
  this._state = DISCONNECTED;
  this._handlers = [];
  this._wireFormat = new WireFormat()
  if (options['buffered']) {
    this._buffer = new FileBuffer(options.bufferPath||BUFFER_PATH);
    this._buffer.on('data', this.send);
  }
}

util.inherits(ServerClient, events.EventEmitter);

ServerClient.RECONNECT_SCHEDULE = RECONNECT_SCHEDULE
ServerClient.BUFFER_PATH = BUFFER_PATH
ServerClient.EVENT_NAMES = EVENT_NAMES

_.extend(ServerClient.prototype, {
  connect: function() {
    if (this.state != CONNECTING && !this.isConnected()) this._establishConnection();
  },
  send: function(msg) {
    var m = this._prepareForSend(msg);
    if (this.isConnected()) {
      this._client.send(m);
    } else this._buffer.write(m);
  },
  isConnected: function() {
    return this._state == CONNECTED;
  },
  isBuffered: function() {
    return !!this._buffer;
  },
  _establishConnection: function() {
    if (this._scheduledReconnect) {
      clearTimeout(this._scheduledReconnect);
      this._scheduledReconnect = null;
    }
    if (!this.isConnected()) {
      this._client = client = new WebSocket.Client(this.uri);
      var self = this;

      client.onopen = function(evt) {
        console.info("connected to " + self.uri);
        self._connected();
      }
      client.onclose = function(evt) {
        self._state = self._skipReconnect ? DISCONNECTING : RECONNECTING
        self.emit(self._state == DISCONNECTING ? "disconnected" : "reconnecting");
        self._reconnect();
      }
      client.onerror = function(evt) {
        if (!self.quiet) console.error("Couldn't connect to " + self.uri);
        self.emit("error");
      }
      client.onmessage = function(evt) {
        self.emit("receive", self._preprocessInMessage(evt.data));
      }
    }
  },
  _reconnect: function () {
    if (this._skipReconnect) {
      this._state = DISCONNECTED;
      this.buffer.close();
    } else {
      if (this._retries && this._retries.length > 0) {
        this._doReconnect(this._retries.shift());
      } else {
        if (this.retryIndefinitely) {
          this._doReconnect(this.retrySchedule[this.retrySchedule.length - 1]);
        } else {
          throw new Error("Exhausted the retry schedule. The server at "+this.uri+" is just not there.");
        }
      }
    }
  },
  _doReconnect: function (retryIn) {
    if (!this._scheduledReconnect) {
      var secs = "second" + (retryIn == 1 ? "" : "s");
      var out = retryIn < 1 ? retryIn * 1000 : retryIn;
      console.info("connection lost, reconnecting in "+out+" "+(retryIn < 1 ? "millis" : secs));
      var self = this;
      this._scheduledReconnect = setTimeout(function() { self._establishConnection() }, retryIn * 1000);
    }
  },
  _connected: function() {
    if (this._buffer) this._buffer.drain();
    this._retries = _.clone(this.retrySchedule);
    this._state = CONNECTED;
    this.emit("connected");
  },
  _preprocessInMessage: function(message) {
    return message;
  },
  _prepareForSend: function(message) {
    var out = this._wireFormat.renderOutMessage(message);
    // TODO: handle handle wrapping a message in an ack.
    return out;
  }
});

