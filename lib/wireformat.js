var _ = require('underscore');

var JsonWireFormat = function(options) {
  this.format = 'json';
}

_.extend(WireFormat.prototype, {
  parseInMessage: function(message) {
    var possiblyJson = content.trim.startsWith("{")
  },
  parseOutMessage: function(message) {

  },
  renderOutMessage: function(message) {

  }
  _inferInMessageContent: function(message) {

  },
  _inferOutMessageContent: function(message) {

  },
  _renderFrom: function(message) {

  }
});

module.exports.WireFormat = WireFormat;