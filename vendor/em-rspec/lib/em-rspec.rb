require 'eventmachine'
require 'rspec/mocks'
require 'set'

module EM
  module RSpec
    
    ROOT = File.expand_path(File.dirname(__FILE__))
    autoload :AsyncSteps, ROOT + '/em-rspec/async_steps'
    autoload :FakeClock, ROOT + '/em-rspec/fake_clock'
    
    def self.async_steps(&block)
      AsyncSteps.new(&block)
    end
  end
end

