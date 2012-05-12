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
        @memory_buffer = options[:memory_buffer] || []
        @state = options[:initial_state] || BufferState::Closed
        @handlers = {}
      end

      def on(event_name, &cb)
        @handlers[event_name] ||= [] 
        @handlers[event_name] << cb
      end

      def remove_on(evt_id) 
        @handlers.unsubscribe(evt_id)
      end

      def open 
        open_file(true) unless open?
        open?
      end

      def open?
        @state > BufferState::Draining
      end

      def close 
        if @stream && !@stream.closed? && open?
          @stream.close 
          @state = BufferState::Closed
          emit(:close)
        end
      end

      def write(message, &callback)
        case @state
        when BufferState::Open
          flush_memory_buffer
          @stream.puts(message)
          callback.call if callback
        when BufferState::Closed
          self.open
          write(message)
        else
          @memory_buffer.push message
          callback.call if callback
        end
      end
      alias :<< :write

      def drain 
        @state = BufferState::Draining
        @stream.close unless @stream.closed?
        File.open(@path, "r:utf-8") do |file|
          while(line = file.gets)
            emit(:data, line.chomp.strip)
          end
        end
        open_file false
      end

      private 
        def emit(evt_name, *args)
          (@handlers[evt_name]||[]).each do |cb| 
            cb.call(*args)
          end
        end

        def open_file(append)
          begin
            @state = BufferState::Opening
            begin
              FileUtils.mkdir_p(File.dirname(@path)) unless File.exist?(File.dirname(@path))
            rescue
              @state = BufferState::Closed
              emit(:error, Exception.new("Can't create the path for the file buffer."))  
            end
            @state = BufferState::Open
            @stream = File.open(@path, "#{append ? 'a' : 'w'}:utf-8")
            emit(:open) if append
            flush_memory_buffer
          rescue Exception => e
            @state = BufferState::Closed
            emit(:error, e)
          end
        end

        def flush_memory_buffer()
          emit(:data, @memory_buffer.pop) until @memory_buffer.empty?
        end
    end
  end
end