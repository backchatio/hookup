# -*- encoding: utf-8 -*-

require 'time'
require "addressable/uri"
require 'faye/websocket'
require 'json'
require 'backchat_websocket/version'
require 'backchat_websocket/errors'

module Backchat
  module WebSocket
    autoload :Client, "#{File.dirname(__FILE__)}/backchat_websocket/client"
    autoload :WireFormat, "#{File.dirname(__FILE__)}/backchat_websocket/wire_format"
    autoload :FileBuffer, "#{File.dirname(__FILE__)}/backchat_websocket/file_buffer"
  end
end