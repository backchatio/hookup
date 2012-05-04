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
      var prsd = JSON.parse(message);
      if (prsd.type === "ack" || prsd.type == "needs_ack") return prsd;
      if (prsd.type === "ack_request") 
        return _.extend(prsd, { type: prsd.type, content: this.parseMessage(prsd.content)});
      return _.extend({type: "json"}, { content: prsd }); 
    } catch (e) {
      return { type: "text", content: message };
    }
  },
  renderMessage: function(message) {
    return JSON.stringify(this.buildMessage(message));
  },
  buildMessage: function(message) {
    if (typeof message === 'object') {
      var prsd = this.parseMessage(message)
      if (prsd.type === "ack_request") 
        prsd.content = this.parseMessage(prsd.content);
      var built = _.extend({type: "json"}, prsd);
      if (built.type === "json" && !built.content) return { type: "json", content: prsd};
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
    return !!message.match(/^(?:\{|\[)/);
  }
});

module.exports = WireFormat;