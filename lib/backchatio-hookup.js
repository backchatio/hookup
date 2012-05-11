var WebSocket = require('faye-websocket'),
    _ = require('underscore'),
    util = require('util'),
    events = require('events'),
    FileBuffer = require("./filebuffer").FileBuffer,
    WireFormat = require("./wireformat");
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

/**
 * Creates a new hookup client 
 * @constructor
 *
 * @description
 *
 * The Hookup client provides a client for the hookup server, it doesn't lock you in to using a specific message format.
 * The default implementation uses websockets to communicate with the server.
 * You can opt-in or opt-out of every feature in the hookup client through configuration.
 * 
 * Usage of the simple hookup client:
 *
 * <pre>
 *   var myHookup = new Hookup({uri: "ws://localhost:8080/thesocket"});
 *   myHookup.on("open", function() {
 *     console.log("The websocket to"+myHookup.uri+" is connected.")''
 *   }); 
 *   myHookup.on("close", function() {
 *     console.log("The websocket to"+myHookup.uri+" disconnected.")''
 *   }); 
 *   myHookup.on("data", function(data) {
 *     if (data.type == "text") {
 *       console.log("RECV: "+data.content);
 *       myHookup.send("ECHO: "+data.content);
 *     }
 *   });
 * </pre>
 */
var ServerClient = exports.Hookup = function (options) {
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
  this._handlers = options.handlers || [];
  this._wireFormat = options.wireFormat || new WireFormat();
  this._expectedAcks = {};
  this._ackCounter = 0;
  // this option `raiseAckEvents` is only useful in tests for the acking itself.
  // it raises an event when an ack is received or an ack request is prepared
  // it serves no other purpose than that, ack_failed events are raised independently from this option.
  this._raiseAckEvents = options.raiseAckEvents === true; 

  if (options.buffer || options.buffered) {
    this._buffer = options.buffer || new FileBuffer(options.bufferPath||BUFFER_PATH);
    this._buffer.on('data', this.send);
  }
}

util.inherits(ServerClient, events.EventEmitter);

/**
 * The default reconnection schedule
 * This is an array of 2 numbers [min, max]
 *
 * @returns {Number[]} The minimum and maximum wait for a reconnection attempt
 */
ServerClient.RECONNECT_SCHEDULE = RECONNECT_SCHEDULE
/**
 * The default path for the file buffer to write to.
 *
 * @returns {String} The path to the file.
 */
ServerClient.BUFFER_PATH = BUFFER_PATH
/**
 * the event names
 *
 * @returns {String} The path to the file.
 */
ServerClient.EVENT_NAMES = EVENT_NAMES

_.extend(ServerClient.prototype, {
  /**
   * Connect to the server
   */
  connect: function() {
    if (this.state != CONNECTING && !this.isConnected()) this._establishConnection();
  },
  /**
   * Disconnect from the socket, perform closing handshake if necessary
   */
  close: function() {
    this._skipReconnect = true;
    this._client.close();
  },
  /**
   * Send a message over the current connection, buffer the message if not connected and the client is 
   * configured to buffer messages.
   *
   * @param {String|Object} message The message to send
   */
  send: function(message) {
    var m = this._prepareForSend(message);
    if (this.isConnected()) {
      this._client.send(m);
    } else if(this.isBuffered()) 
      this._buffer.write(m);
  },
  /**
   * Send a message over the current connection and request that this message will be acked upon receipt by the server.
   * Buffers the message if not connected and the client is configured to buffer messages.
   *
   * @param {String|Object} message The message to send
   */
  sendAcked: function(message, options) {
    var timeout = (options||{})['timeout']||5000;
    this.send({type: "needs_ack", content: message, timeout: timeout});
  },
  /**
   * A flag indicating connection status of the client.
   *
   * @returns true when the client is connected.
   */
  isConnected: function() {
    return this._state == CONNECTED;
  },
  /**
   * A flag indicating if this client should fallback to buffering upon disconnection.
   */
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
      this._notifiedOfReconnect = true;
      if (this._reconnectIn && this._reconnectIn > 0 && this._reconnectIn < this.reconnectSchedule.max) {
        var curr = this._reconnectIn;
        var max = this.reconnectSchedule.max;
        var next = curr < max ? curr : max;
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
      this._reconnectIn = this._reconnectIn * 2;
    }
  },
  _connected: function() {
    if (this._buffer) this._buffer.drain();
    this._reconnectIn = this.reconnectSchedule.min;
    this._reconnectsLeft = 0;
    this._notifiedOfReconnect = false;
    this._skipReconnect = false;
    this._state = CONNECTED;
    this.emit("open");
  },
  _preprocessInMessage: function(msg) {
    var message = this._wireFormat.parseMessage(msg);
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

