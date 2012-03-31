var _ = require('underscore');

var JsonWireFormat = function(options) {
  this.format = 'json';
}

_.extend(WireFormat.prototype, {
  parseInMessage: function(message) {
    if (!this._canBeJson()) 
      return { type: "text", "content": message };
    try {
      return _.extend({type: "json"}, JSON.parse(message));
    } catch (e) {
      return { type: "text", "content": message };
    }
  },
  parseOutMessage: function(message) {
    if (!this._canBeJson()) 
      return { type: "text", "content": message };
    try {
      return _.extend({type: "json"}, JSON.parse(message));
    } catch (e) {
      return { type: "text", "content": message };
    }
  },
  renderOutMessage: function(message) {
    if (typeof message === 'object') {
      return JSON.stringify(message);
    } else {
      return message.toString();
    }
  },
  _canBeJson: function(message) {
    return !!message.match(/^(?:\{|\]/);
  }
});

module.exports.WireFormat = WireFormat;