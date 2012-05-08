# -*- encoding: utf-8 -*-
require 'bundler/setup'
require 'rainbows'
require 'faye/websocket'
require 'json'
Unicorn::Configurator::DEFAULTS[:logger] = Logger.new(StringIO.new)

$:.unshift File.expand_path('../../lib', __FILE__)
require File.expand_path('../../vendor/em-rspec/lib/em-rspec', __FILE__)

class TestServer
  def call(env)
    socket = Faye::WebSocket.new(env, ["echo"])
    socket.onmessage = lambda do |event|
      puts "SERVER: #{event.data}"
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
    begin
      @server.stop if @server
    rescue
    end
  end
end

require 'backchatio-websocket'
include Backchat::WebSocket