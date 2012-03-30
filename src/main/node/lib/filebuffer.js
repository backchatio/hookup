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
      var self = this;
      this._openFile(true);
      this._stream.once('open', function() {
        self.emit('open');
      })
    }
    return this._state < DRAINING;
  },
  close: function() {
    if (this._state < DRAINING) {
      var self = this;
      this._stream.once("close", function() { self.emit("close") });
      this._stream.end();
    } else this.emit("close");
  },
  write: function (data, callback) {
    var outMessage = data; // TODO: Add formatter for serialization
    var self = this;
    switch(this._state) {
      case OPEN:
        self._stream.write(self._memoryBuffer.join("\n") + "\n");
        self._memoryBuffer = [];
        process.nextTick(function() {
          self._stream.write(outMessage + "\n");
          callback(null, true);
        });
        break;
      case CLOSED:
        this.open();
        this.once("open", function() {
          self.write(data, callback);
        });
        break;
      default:
        this._memoryBuffer.push(outMessage);
        callback(null, true);
    }
  },
  drain: function() {
    var self = this;
    var jnl = this._stream;
    jnl.once('close', function() {
      var rdJnl = fs.createReadStream(self.path, {encoding: 'utf8'});
      rdJnl.on('data', function(data) {
        data.split("\n").forEach(function(line) {
          if (line && line.trim().length > 0) {
            self.emit('data', line); // TODO: make this use a deserializer of some sort.
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
          self._stream.write(self._memoryBuffer.join("\n") + "\n");
          self._memoryBuffer = [];
        });
      } catch (e) {
        self.emit("error", e);
      }
    });
  }
});
exports = exports.FileBuffer = FileBuffer;