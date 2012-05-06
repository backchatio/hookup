# -*- encoding: utf-8 -*-

require 'time'
require "addressable/uri"
require 'faye/websocket'
require 'json'
require 'backchatio-websocket/version'
require 'backchatio-websocket/errors'

module Backchat
  module WebSocket
    autoload :Client, "#{File.dirname(__FILE__)}/backchatio-websocket/client"
    autoload :WireFormat, "#{File.dirname(__FILE__)}/backchatio-websocket/wire_format"
    autoload :FileBuffer, "#{File.dirname(__FILE__)}/backchatio-websocket/file_buffer"
    autoload :Eventable, "#{File.dirname(__FILE__)}/backchatio-websocket/eventable"
  end
end