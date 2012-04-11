var _ = require('underscore'),
    util = require('util');

var WireFormat = function(options) {
  this.format = 'json';
}

_.extend(WireFormat.prototype, {
  parseMessage: function(message) {
    if (typeof message === 'object') return message;
    if (!this._canBeJson(message)) 
      return { type: "text", content: message };
    try {
      var parsed = _.extend({type: "json"}, JSON.parse(message));
      if (parsed.type === "ack_request") 
        parsed.content = this.parseMessage(parsed.content);
      return parsed;
    } catch (e) {
      return { type: "text", content: message };
    }
  },
  renderMessage: function(message) {
    return JSON.stringify(this.buildMessage(message));
  },
  buildMessage: function(message) {
    if (typeof message === 'object') {
      var built = _.extend({type: "json"}, this.parseMessage(message));
      if (built.type === "ack_request") 
        built.content = this.parseMessage(built.content);
      return built;
    } else {
      return { type: "text", content: message.toString() };
    }
  },
  unwrapContent: function(message) {
    var parsed = this.parseMessage(message);
    if (parsed.type === "ack_request") 
      return parsed.content.content;
    if (parsed.type === "json") {
      delete parsed['type'];
      return Object.keys(parsed).length === 1 && parsed.content ? parsed.content : parsed;
    }
    if (parsed.type === "ack" || parsed.type === "needs_ack") return parsed;
    return parsed.content;
  },
  _canBeJson: function(message) {
    return !!message.match(/^(?:\{|\[])/);
  }
});

module.exports.WireFormat = WireFormat;