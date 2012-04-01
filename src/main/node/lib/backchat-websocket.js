var WebSocket = require('faye-websocket'),
    _ = require('underscore'),
    util = require('util'),
    events = require('events'),
    FileBuffer = require("./filebuffer").FileBuffer,
    WireFormat = require("./wireformat").WireFormat,
    fs = require('fs'),
    Uri = require('url');


var RECONNECT_SCHEDULE = {min: 1, max: 300};
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
  if (!options.uri || options.uri.trim().length == 0) throw new Error('`uri` option is required.');
  this.uri = options.uri;
  this._uri = Uri.parse(this.uri.replace(/^http/i, 'ws'));
  if (!this._uri.host || !((this._uri.protocol||"").match(/^ws/))) throw new Error("Invalid uri supplied.")
  delete options['uri'];
  
  this.reconnectSchedule = options.reconnectSchedule || RECONNECT_SCHEDULE;

  this._quiet = options.quiet;
  this._state = DISCONNECTED;
  this._handlers = [];
  this._wireFormat = new WireFormat();
  this._expectedAcks = {};
  this._ackCounter = 0;
  // this option `raiseAckEvents` is only useful in tests for the acking itself.
  // it raises an event when an ack is received or an ack request is prepared
  // it serves no other purpose than that, ack_failed events are raised independently from this option.
  this._raiseAckEvents = options.raiseAckEvents === true; 

  if (options.buffered) {
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
  close: function() {
    this._skipReconnect = true;
    this._client.close();
  },
  send: function(message) {
    var m = this._prepareForSend(message);
    if (this.isConnected()) {
      this._client.send(m);
    } else this._buffer.write(m);
  },
  sendAcked: function(message, options) {
    var timeout = (options||{})['timeout']||5000;
    this.send({type: "needs_ack", content: message, timeout: timeout});
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
        self.emit("error", evt.data);
      }
      client.onmessage = function(evt) {
        var inMsg = self._preprocessInMessage(evt.data);
        if (inMsg)
          self.emit("data", inMsg);
      }
    }
  },
  _reconnect: function () {
    if (this._state == DISCONNECTING) {
      this._doDisconnect();
    } else {
      if (!this._notifiedOfReconnect) {
        this._notifiedOfReconnect = true;
      }
      if (this._reconnectIn && this._reconnectIn > 0 && this._reconnectIn < this.reconnectSchedule.max) {
        var nextRecon = this._reconnectIn * 2;
        var max = this.reconnectSchedule.max;
        var next = nextRecon < max ? nextRecon : max;
        if (this.reconnectSchedule.maxRetries && this.reconnectSchedule.maxRetries > 0) {
          if (this._reconnectsLeft <= 0)
            this.emit('error', new Error("Exhausted the retry schedule. The server at "+this.uri+" is just not there."));
          else
            --this._reconnectsLeft;
        } 
        this._doReconnect(next);
      } else {
        this._doDisconnect();    
      }
    }
  },
  _doDisconnect: function() {
    this._state = DISCONNECTED;
    this.emit('close');
    if (this._buffer) this.buffer.close();
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
    this._reconnectIn = this.reconnectSchedule.min;
    this._reconnectsLeft = 0;
    this._notifiedOfReconnect = false;
    this._skipReconnect = false;
    this._state = CONNECTED;
    this.emit("connected");
  },
  _preprocessInMessage: function(msg) {
    var message = this._wireFormat.parseMessage(msg);
    console.log("preprocessing: " + util.inspect(message)); 
    if (message.type === "ack_request") {
      this.send({type: "ack", id: message.id});
    }
    if (message.type === "ack") {
      var timeout = this._expectedAcks[message.id]; 
      if (timeout) clearTimeout(timeout);
      delete this._expectedAcks[message.id];
      if (this._raiseAckEvents) this.emit("ack", message);
      return null;
    }
    return this._wireFormat.unwrapContent(message);
  },
  _prepareForSend: function(message) {
    var out = this._wireFormat.renderMessage(message);
    var self = this;
    if (message.type === "needs_ack") {
      var ackReq = {
        content: this._wireFormat.buildMessage(message.content),
        type: "ack_request",
        id: ++this._ackCounter
      };
      this._expectedAcks[ackReq.id] = setTimeout(function (){ self.emit('ack_failed', message.content) }, message.timeout);
      out = this._wireFormat.renderMessage(ackReq);
      if (this._raiseAckEvents) this.emit("ack_request", ackReq);
    }
    return out;
  }
});

