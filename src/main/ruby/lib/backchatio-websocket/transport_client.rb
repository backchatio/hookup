# -*- encoding: utf-8 -*-
require 'thread'
module Backchat
  module WebSocket

    class TransportClient

      attr_reader :uri
      
      def initialize(options={})
        options = {:uri => options} if options.is_a?(String)
        raise Backchat::WebSocket::UriRequiredError, ":uri parameter is required" unless options.key?(:uri)
        parsed = begin
          u = Addressable::URI.parse(options[:uri].gsub(/^http/i, 'ws')).normalize
          u.path = "/" if u.path.nil? || u.path.strip.empty?
          u.to_s
        rescue 
          raise Backchat::WebSocket::InvalidURIError, ":uri [#{options[:uri]}] must be a valid uri" 
        end
        @uri = parsed
        @state = ClientState::Disconnected
       end

      def on(event_name, &cb)
        ((@handlers||={})[event_name]||=[]) << cb
      end

      def remove_on(event_name, cb) 
        (@handlers||={})[event_name] = ((@handlers||{})[event_name]||[]).delete(cb)
      end


      private 
        def emit(event_name, args = nil)
          @handlers[event_name].each do |cb|
            args ? cb.call(args) : cb.call
          end
        end

    end
  end
end