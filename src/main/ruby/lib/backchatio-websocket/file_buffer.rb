# -*- encoding: utf-8 -*-

require 'fileutils'

module Backchat
  module WebSocket

    module BufferState
      Closed = 0
      Draining = 1
      Opening = 2
      Open = 3
      Overflow = 4
    end

    class FileBuffer

      attr_reader :path 

      def initialize(path, options={})
        @path = path
        @memory_buffer = []
        @state = options[:state] || BufferState::Closed
        @handlers = EM::Channel.new
      end

      def on(event_name, &cb)
        @handlers.subscribe do |evt|
          callback = EM.Callback(&cb)
          callback.call if evt.size == 1
          callback.call(evt[1]) if evt[0] == event_name && evt.size > 1
        end
      end

      def remove_on(evt_id) 
        @handlers.unsubscribe(evt_id)
      end

      def open 
        open_file(true) unless @state < BufferState::Opening
        open?
      end

      def open?
        @state < BufferState::Draining
      end

      def close 
        @stream.close if open? and not @stream.closed?
        @stated = BufferState::Closed
        emit(:close)
      end

      def write(message)
        case @state
        when BufferState::Opening
          flush_memory_buffer
          EM.next_tick do 
            @stream.write("#{message}\n")
            yield if block_given?
          end
        when BufferState::Closed
          self.open
          write(message)
        else
          @memory_buffer.push message
          yield if block_given?
        end
      end
      alias :<< :write

      def drain 
        @state = BufferState::Draining
        EM.next_tick do
          @stream.close unless @stream.closed?
          File.open(@path, "r:utf-8") do |file|
            while(line = file.gets)
              emit(:data, line.chomp.strip)
            end
          end
          open_file false
        end
      end

      private 
        def emit(evt_name, args = nil)
          @handlers.publish(args ? [evt_name, args] : [evt_name])
        end

        def open_file(append)
          begin
            @state = BufferState::Opening
            begin
              FileUtils.mkdir_p(File.dirname(@path))
            rescue
              @state = BufferState::Closed
              emit(:error, Exception.new("Can't create the path for the file buffer."))  
            end
            @state = BufferState::Open
            emit(:open) if append
            @stream = File.open(@path, "#{append ? 'a' : 'w'}:utf-8")
            flush_memory_buffer
          rescue Exception => e
            @state = BufferState::Closed
            emit(:error, e)
          end
        end

        def flush_memory_buffer()
          @memory_buffer.pop until @memory_buffer.empty?
        end
    end
  end
end