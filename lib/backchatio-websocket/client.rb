# -*- encoding: utf-8 -*-
require 'thread'

module Backchat
  module WebSocket

    RECONNECT_SCHEDULE = 1..300
    BUFFER_PATH = "./logs/journal.log"
    EVENT_NAMES = {
      :receive => "message",
      :connected => "open",
      :disconnect => "close"
    }

    module ClientState
      Disconnected = 0
      Disconnecting = 1
      Reconnecting = 2
      Connecting = 3
      Connected = 4
    end

    class Client

      attr_reader :uri, :retry_schedule

      def on(event_name, &cb)
        @handlers.subscribe do |evt|
          callback = EM.Callback(&cb)
          callback.call if evt.size == 1 and evt[0] == evt_name
          callback.call(evt[1]) if evt[0] == event_name && evt.size > 1
        end
      end

      def remove_on(evt_id) 
        @handlers.unsubscribe(evt_id)
      end

      private
      def emit(evt_name, args)
        @handlers << [evt_name, args]
      end

      public
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
        @uri, @retry_schedule = parsed, (options[:reconnect_schedule]||RECONNECT_SCHEDULE.clone)
        @quiet = !!options[:quiet]
        @handlers = EM::Channel.new
        @wire_format = WireFormat.new
        @expected_acks = {}
        @ack_counter = 0
        # this option `raise_ack_events` is only useful in tests for the acking itself.
        # it raises an event when an ack is received or an ack request is prepared
        # it serves no other purpose than that, ack_failed events are raised independently from this option.
        @raise_ack_events = !!options[:raise_ack_events]
        @state = ClientState::Disconnected
        if !!options[:buffered]
          @journal = FileBuffer.new(options[:buffer_path]||BUFFER_PATH)
          @journal.on(:data, &method(:send))
        end
      end

      def send(msg)
        m = prepare_for_send(msg)
        if connected?          
          @ws.send(m)
        else
          @journal << m if buffered?
        end
      end

      def send_acked(msg, options={})
        timeout = (options||{})[:timeout]||5
        self.send(:type => :needs_ack, :content => msg, :timeout => timeout)
      end

      def connect
        establish_connection unless @state < ClientState::Connecting
      end

      def connected?
        @state == ClientState::Connected
      end

      def buffered?
        !!@journal
      end

      def disconnect
        if @state > ClientState::Reconnecting
          @skip_reconnect = true
          @ws.close
        end
      end

      def method_missing(name, *args, &block)
        if name =~ /^on_(.+)$/ 
          on($1, &block)
        elsif name =~ /^remove_on_(.+)$/
          remove_on($1, &block)
        else
          super
        end
      end

      private
        def reconnect
          if @state == ClientState::Disconnecting
            perform_disconnect
          else
            @notified_of_reconnect = true
            if @reconnect_in && @reconnect_in > 0 && @reconnect_in < @reconnect_schedule.max
              curr = @reconnect_in
              max = @reconnect_schedule.max
              nxt = curr < max ? curr : max
              if @max_retries && @max_retries > 0
              else
                
              end
            else
              perform_disconnect
            end
          end
          unless @skip_reconnect          
            unless @retries.nil? || @retries.empty?
              retry_in = @retries.shift 
              secs = "second#{retry_in == 1 ? "" : "s"}"
              puts "connection lost, reconnecting in #{retry_in >= 1 ? retry_in : retry_in * 1000} #{retry_in >= 1 ? secs : "millis"}"
              EM.add_timer(retry_in) { establish_connection }
            else 
              if @retry_indefinitely
                raise ServerDisconnectedError, "Exhausted the retry schedule. The server at #{uri} is just not there."
              else
                retry_in = @retry_schedule.last
                secs = "second#{retry_in == 1 ? "" : "s"}"
                puts "connection lost, reconnecting in #{retry_in >= 1 ? retry_in : retry_in * 1000} #{retry_in >= 1 ? secs : "millis"}"
                @scheduled_retry = EM::Timer.new(retry_in) { establish_connection }
              end
            end
          else
            @state == :disconnected
          end
        end

        def establish_connection
          if @scheduled_retry
            @scheduled_retry.cancel
            @scheduled_retry = nil
          end
          unless connected?
            begin
              @ws = Faye::WebSocket::Client.new(@uri)
              @state = ClientState::Connecting

              @ws.onopen = lambda { |e| 
                puts "connected to #{uri}"
                self.connected
              }
              @ws.onmessage = lambda { |e|
                m = self.preprocess_in_message(e)
                emit(:receive, e)
              }
              @ws.onerror = lambda { |e| 
                unless @quiet
                  puts "Couldn't connect to #@uri"
                  puts e.inspect 
                end
                emit(:error, e)
              }
              @ws.onclose = lambda { |e| 
                @state = @skip_reconnect ? ClientState::Disconnecting : ClientState::Reconnecting
                if @state == ClientState::Disconnecting
                  emit(:disconnected, e)
                else
                  emit(:reconnect, e)
                end
                reconnect 
              }
            rescue Exception => e
              puts e
              emit(:error, e)
            end
          end
        end

        def flush_journal_to_server
          @journal.close
          IO.foreach(BUFFER_PATH) do |line|
            @ws.send(line)
          end
          while entry = (@journal_buffer||[]).shift
            @ws.send(line)
          end
          @state = :connected
          @journal = File.open(BUFFER_PATH, 'w')
        end

        def connected

        end
        

    end
  end
end