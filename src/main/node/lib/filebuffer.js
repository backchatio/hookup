var _ = require('underscore'),
    util = require('util'),
    events = require('events'),
    mkdirp = require('mkdirp'),
    fs = require('fs'),
    path = require('path');

var CLOSED = 0, DRAINING = 1, OPENING = 2, OPEN = 3, OVERFLOW = 4;

var FileBuffer = function(path, extra) {

  events.EventEmitter.call(this);
  this.path = path;
  var ex = extra||{};
  this._memoryBuffer = ex.memoryBuffer || [];
  this._state = ex.initialState || CLOSED;
}

util.inherits(FileBuffer, events.EventEmitter);

_.extend(FileBuffer.prototype, {
  open: function() {
    if (this._state < OPENING) {
      this._openFile(true);
    }
    return this._state < DRAINING;
  },
  close: function() {
    if (this._state < DRAINING) {
      var self = this;
      this._stream.once("close", function() { 
        self.emit("close");
        self._state = CLOSED;
      });
      this._stream.end();
    } else {
      this.emit("close");
      this._state = CLOSED;
    }
  },
  write: function (outMessage, callback) {
    var self = this;
    switch(this._state) {
      case OPEN:
        this._flushMemoryBuffer();
        process.nextTick(function() {
          self._stream.write(outMessage + "\n");
          if (callback) callback(null, true);
        });
        break;
      case CLOSED:
        this.open();
        this.once("open", function() {
          self.write(outMessage, callback);
        });
        break;
      default:
        this._memoryBuffer.push(outMessage);
        if (callback) callback(null, true);
    }
  },
  drain: function() {
    var self = this;
    var jnl = this._stream;
    jnl.once('close', function() {
      var rdJnl = fs.createReadStream(self.path, {encoding: 'utf8'});
      rdJnl.on('data', function(data) {
        data.split("\n").forEach(function(line) {
          var cleaned = (line||"").replace(/(\n|\r)+$/, '').trim();
          if (cleaned.length > 0) {
            self.emit('data', cleaned);
          }
        })
      });
      rdJnl.on('close', function() {
        self._openFile(false);
      });
    });
    jnl.end();
  },
  _openFile: function(append) {
    this._state = OPENING;
    var bufferDir = path.dirname(this.path);
    var self = this;
    mkdirp(bufferDir, function(err, data) {
      if (err) {
        self.emit("error", new Error("Can't create the path for the file buffer"));
      }
      try {
        self._stream = fs.createWriteStream(self.path, { flags: append ? 'a' : 'w', encoding: 'utf8'});
        self._stream.once('open', function(fd) { 
          self._state = OPEN;
          if (append) self.emit('open');
          self._flushMemoryBuffer();
        });
      } catch (e) {
        self.emit("error", e);
      }
    });
  },
  _flushMemoryBuffer: function() {
    var self = this;
    if (self._memoryBuffer.length > 0) {
      self._stream.write(self._memoryBuffer.join("\n") + "\n");
      self._memoryBuffer = [];
    }
  }
});
exports = exports.FileBuffer = FileBuffer;