# -*- encoding: utf-8 -*-

require 'time'
require "addressable/uri"
require 'faye/websocket'
require 'yajl/json_gem'
require 'backchat_websocket/version'
require 'backchat_websocket/errors'

module Backchat
  module WebSocket
    autoload :Client, "#{File.dirname(__FILE__)}/backchat_websocket/client"
  end
end