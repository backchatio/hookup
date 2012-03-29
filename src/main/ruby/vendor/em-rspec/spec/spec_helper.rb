require File.dirname(__FILE__) + '/../lib/em-rspec'

Thread.new { EM.run }
sleep 0.1 until EM.reactor_running?

