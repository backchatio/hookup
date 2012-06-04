var _ = require('underscore'),
    util = require('util');

/**
 * Creates a new wireformat
 * @class
 * A protocol that supports all the features of the websocket server.
 * This wireformat knows about acking and the related protocol messages.
 * it uses a json object to transfer, meaning everything has a property name.
 */
var WireFormat = function(options) {
  var opts = options||{};
  this.format = opts.protocol || 'json';
  this.name = opts.name || "simpleJson";
}

_.extend(WireFormat.prototype, /** @lends WireFormat.prototype */ {
  /**
   * Parses a message from a string, and detects which type of message it is.
   * It detects if the message is a json string, and then works out if the message is
   * a protocol message or a user message and builds the appropriate data structure.
   *
   * This method is used when a message is received from a remote party.
   * The method is also used when draining a buffer, to read the string entries in the buffer.
   *
   * @param {String} message The string representation of the message to parse.
   * @returns {Object} The message object with meta data attached.
   */
  parseMessage: function(message) {
    if (typeof message === 'object') return message;
    if (this.name === "jsonProtocol") {
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
    } else {
      if (!this._canBeJson(message)) 
        return message;
      try {
        return JSON.parse(message);
      } catch (e) {
        return message;
      }
    }
  },
  /**
   * Render a message to a string for sending over the socket.
   *
   * @param {Object} message The message to serialize.
   * @returns {String} The string representation of the message to be sent.
   */
  renderMessage: function(message) {
    return typeof message === "string" ? message : JSON.stringify(this.buildMessage(message));
  },
  /**
   * Builds a message from a string or an object and wraps it in an envelope with meta data for the protocol.
   * 
   * @param {String|Object} message The message to send.
   * @returns {Object} The message wrapped and ready to send.
   */
  buildMessage: function(message) {
    if (this.name === "jsonProtocol") {
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
    } else {
      if (typeof message === 'object') {
        return this.parseMessage(message)
      } else {
        return message.toString();
      }
    }
  },
  /**
   * Unwraps the message from the envelope, this is used before raising the data event on a hookup client.
   * 
   * @param {Object|String} message The message for which to get the content.
   * @returns {String|Object} The content of the message if any.
   */
  unwrapContent: function(message) {
    var parsed = this.parseMessage(message);
    if (this.name === "jsonProtocol") {
      if (parsed.type === "ack_request") 
        return parsed.content.content;
      if (parsed.type === "json") {
        delete parsed['type'];
        return Object.keys(parsed).length === 1 && parsed.content ? parsed.content : parsed;
      }
      if (parsed.type === "ack" || parsed.type === "needs_ack") return parsed;
      return parsed.content;
    } else {
      return parsed;
    }
  },
  _canBeJson: function(message) {
    return !!message.match(/^(?:\{|\[)/);
  }
});

module.exports.WireFormat = WireFormat;

