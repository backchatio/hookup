var _ = require('underscore');

var WireFormat = function(options) {
  this.format = 'json';
}

_.extend(WireFormat.prototype, {
  parseMessage: function(message) {
    if (typeof message === 'object') return message;
    if (!this._canBeJson()) 
      return { type: "text", content: message };
    try {
      return _.extend({type: "json"}, JSON.parse(message));
    } catch (e) {
      return { type: "text", content: message };
    }
  },
  renderOutMessage: function(message) {
    return JSON.stringify(this.buildOutMessage(message));
  },
  buildOutMessage: function(message) {
    if (typeof message === 'object') {
      return _.extend({type: "json"}, message);
    } else {
      return { type: "text", content: message.toString() };
    }
  },
  unwrapContent: function(message) {
    var parsed = this.parseMessage(message);
    if (parsed.type === "ack_request") 
      return this.parseMessage(parsed.content).content;
    return parsed.content;
  },
  _canBeJson: function(message) {
    return !!message.match(/^(?:\{|\]/);
  }
});

module.exports.WireFormat = WireFormat;