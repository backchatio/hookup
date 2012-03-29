var WebSocket = require('faye-websocket'),
    _ = require('underscore'),
    util = require('util'),
    events = require('events'),
    fs = require('fs'),
    Uri = require('url');


var RECONNECT_SCHEDULE = [1, 1, 1, 1, 1, 5, 5, 5, 5, 5, 10, 10, 10, 10, 10, 30, 30, 30, 30, 30, 60, 60, 60, 60, 60, 300, 300, 300, 300, 300];
var JOURNAL_PATH = './logs/journal.log';
var EVENT_NAMES = {
  connected: "open",
  receive: "message",
  disconnected: "close"
}

DISCONNECTED=0
DISCONNECTING=1
RECONNECTING=2
JOURNAL_REDO=3
CONNECTING=4
CONNECTED=5


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
  this._journalPath = options.journalPath||JOURNAL_PATH;
  if (options['journaled']) this.journal = fs.createWriteStream(this._journalPath, { flags: 'a', encoding: 'utf-8'});
  this._journalBuffer = [];
  
}

util.inherits(ServerClient, events.EventEmitter);

ServerClient.RECONNECT_SCHEDULE = RECONNECT_SCHEDULE
ServerClient.JOURNAL_PATH = JOURNAL_PATH
ServerClient.EVENT_NAMES = EVENT_NAMES

ServerClient.prototype.connect = function() {
  if (this.state != CONNECTING && !this.isConnected()) this._establishConnection();
}

ServerClient.prototype.send = function(msg) {
  var m = typeof msg === "string" ? msg : JSON.stringify(msg);
  if (this.isConnected()) {
    this._drainMemoryBuffer();
    this._client.send(m);
  } else if (this._state == JOURNAL_REDO) {
    this._journalBuffer.push(m);
  } else {
    if (this.isJournaled()) {
      var _a = null;
      while(_a = this._journalBuffer.shift()) {
        this.journal.write(a + "\n");
      }
      this.journal.write(m + "\n");
    }
  }
}

ServerClient.prototype.isConnected = function() {
  return this._state == CONNECTED;
}

ServerClient.prototype.isJournaled = function() {
  return !!this.journal;
}

ServerClient.prototype._establishConnection = function() {
  if (this._scheduledReconnect) {
    clearTimeout(this._scheduledReconnect);
    this._scheduledReconnect = null;
  }
  if (!this.isConnected()) {
    this._client = client = new WebSocket.Client(this.uri);
    var self = this;

    client.onopen = function(evt) {
      console.info("connected to " + self.uri);
      self._state = self.isJournaled() && self._state == RECONNECTING ? JOURNAL_REDO : CONNECTED;
      if (self._state == JOURNAL_REDO) self._drainFileBuffer();
      else self._connected();
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
      self.emit("receive", evt.data)
    }
  }
}

ServerClient.prototype._reconnect = function () {
  if (this._skipReconnect) {
    this._state = DISCONNECTED;
    this.journal.end();
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
}

ServerClient.prototype._doReconnect = function (retryIn) {
  if (!this._scheduledReconnect) {
    var secs = "second" + (retryIn == 1 ? "" : "s");
    var out = retryIn < 1 ? retryIn * 1000 : retryIn;
    console.info("connection lost, reconnecting in "+out+" "+(retryIn < 1 ? "millis" : secs));
    var self = this;
    this._scheduledReconnect = setTimeout(function() { self._establishConnection() }, retryIn * 1000);
  }
}

ServerClient.prototype._connected = function() {
  this._drainMemoryBuffer();
  this._retries = _.clone(this.retrySchedule);
  this._state = CONNECTED;
  this.emit("connected");
}

ServerClient.prototype._drainMemoryBuffer = function() {
  if (this._client) {
    var entry = null;
    while(entry = (this._journalBuffer||[]).shift()) {
      this._client.send(entry);
    }
  }
}

ServerClient.prototype._drainFileBuffer = function() {
  var self = this;
  var jnl = this.journal;
  jnl.once('close', function() {
    var rdJnl = fs.createReadStream(self._journalPath, {encoding: 'utf-8'});
    rdJnl.on('data', function(data) {
      data.toString('utf-8').split("\n").forEach(function(line) {
        if (line && line.trim().length > 0) {
          var written = self._client.send(line);
          if (!written) rdJnl.pause();
        }
      })
    });
    rdJnl.on('drain', function() {
      rdJnl.resume();
    })
    // rdJnl.on('end', function() { rdJnl.close() })
    rdJnl.on('close', function() {
      self.journal = fs.createWriteStream(self._journalPath, { flags: 'w', encoding: 'utf-8'});
      self._connected();
    });
  });
  jnl.end();
}
