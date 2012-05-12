var vows = require("vows"),
    assert = require("assert"),
    FileBuffer = require("../lib/filebuffer").FileBuffer,
    rimraf = require('rimraf').sync,
    events = require('events'),
    util = require('util'),
    fs = require('fs'),
    path = require('path');

vows.describe("FileBuffer").addBatch({
  "when opening, ": {
    topic: {
      workPath: "./test-work",
      logPath: "./test-work/testing/and/such/buffer.log"
    },
    "creates the path": {
      topic: function(options) {
        if (path.exists(options.workPath)) rimraf(options.workPath);
        var promise =  new(events.EventEmitter);
        var buff = new FileBuffer(options.logPath);
        buff.on("open", function(){
          buff.close();
        });
        buff.on("error", function(e) {
          promise.emit("error", e);
        });
        buff.on("close", function() {
          buff.workPath = options.workPath;
          promise.emit("success", buff);
        });
        buff.open();
        return promise;
      },
      "if it doesn't exist yet": function(err, res) {
        assert.isTrue(path.existsSync(res.path));
        rimraf(res.workPath);
      }
    }
  },
  "when not draining,": {
    topic: {
      logPath: "./test-work2/buffer.log",
      workPath: "./test-work2",
      exp1: "the first message",
      exp2: "the second message"
    },
    "writes to a file": {
      topic: function(options) {
        if (path.exists(options.workPath)) rimraf(options.workPath);
        var promise =  new(events.EventEmitter);
        var buff = new FileBuffer(options.logPath);
        buff.on('open', function() {
          buff.write(options.exp1, function() {});  
          buff.write(options.exp2, function() {
            buff.close();
          });  
          buff.on("close", function() {
            var lines = fs.readFileSync(options.logPath, 'utf8');
            rimraf(options.workPath);
            promise.emit("success", lines.split("\n"));
          });
          
        })
        buff.open();
        return promise;
      },
      "succeeds": function(err, lines) {
        assert.lengthOf(lines, 2);
      }
    }
  },
  "when draining, ": {
    topic: {
      logPath: "./test-work3/buffer.log",
      workPath: "./test-work3",
      exp1: "the first message",
      exp2: "the second message"
    },
    "new sends": {
      topic: function(options) {
        if (path.exists(options.workPath)) rimraf(options.workPath);
        options.memoryBuffer = [];
        options.buffer = new FileBuffer(options.logPath, { memoryBuffer: options.memoryBuffer, initialState: 1});

        return options;
      },
      "should write to the memory buffer": function(topic) {
        topic.buffer.write(topic.exp1);
        topic.buffer.write(topic.exp2);
        assert.lengthOf(topic.memoryBuffer, 2);
      }
    },
    "a drain request": {
      topic: function(options) {
        if (path.exists(options.workPath)) rimraf(options.workPath);
        var promise = new (events.EventEmitter);
        var buff = new FileBuffer(options.logPath);
        var lines = [];
        var self = this;
        buff.on('data', function(data) {
          lines.push(data);
          if (lines.length === 2) {
            buff.close();
          }
        });
        buff.on("open", function() {
          buff.write(options.exp1);
          buff.write(options.exp2, function() { buff.drain() });
        });
        buff.on("close", function() {
          rimraf(options.workPath);
          self.callback(null, lines);
        });
        buff.open();
      },
      "should raise data events for every line in the buffer": function (err, topic) {
        assert.lengthOf(topic, 2);
      }
    }
  }
}).export(module);