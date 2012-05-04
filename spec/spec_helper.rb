# -*- encoding: utf-8 -*-
require 'bundler/setup'
require 'rainbows'
require 'faye/websocket'
require 'json'
Unicorn::Configurator::DEFAULTS[:logger] = Logger.new(StringIO.new)

$:.unshift File.expand_path('../../lib', __FILE__)

class TestServer
  def call(env)
    socket = Faye::WebSocket.new(env, ["echo"])
    socket.onmessage = lambda do |event|
      socket.send(event.data)
    end
    socket.rack_response
  end
  
  def listen(port)
    rackup = Unicorn::Configurator::RACKUP
    rackup[:port] = port
    rackup[:set_listener] = true
    options = rackup[:options]
    options[:config_file] = File.expand_path('../rainbows.conf', __FILE__)
    @server = Rainbows::HttpServer.new(self, options)
    @server.start
  end

  def restart
    begin
      @server.start
    rescue Exception => e
      puts e
    end
  end
  
  def stop
    @server.stop if @server
  end
end

require 'backchatio-websocket'
include Backchat::WebSocket