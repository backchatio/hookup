module EM::RSpec
  class AsyncSteps < Module
    def included(klass)
      klass.__send__(:include, Sync)
      klass.after { sync } if klass.respond_to?(:after)
    end
    
    def method_added(method_name)
      async_method_name = "async_#{method_name}"
      return if instance_methods(false).map { |m| m.to_s }.include?(async_method_name) or
                method_name.to_s =~ /^async_/
      
      module_eval <<-RUBY
        alias :#{async_method_name} :#{method_name}
        def #{method_name}(*args)
          __enqueue__([#{async_method_name.inspect}] + args)
        end
      RUBY
    end
    
    ADD_TIMER = EM.method(:add_timer)
    
    module Sync
      def __enqueue__(args)
        @__step_queue__ ||= []
        @__step_queue__ << args
        return if @__running_steps__
        @__running_steps__ = true
        ADD_TIMER.call(0, &method(:__run_next_step__))
      end
      
      def __run_next_step__
        step = @__step_queue__.shift
        return @__running_steps__ = false unless step
        
        method_name, args = step.shift, step
        begin
          method(method_name).call(*args) { __run_next_step__ }
        rescue Object => e
          @example.set_exception(e) if @example
          __end_steps__
        end
      end
      
      def __end_steps__
        @__step_queue__ = []
        __run_next_step__
      end
      
      def sync
        Thread.pass while @__running_steps__
      end
    end
    
  end
end

