# -*- encoding: utf-8 -*-

$:.push File.expand_path("../lib", __FILE__)
require "backchatio-hookup/version"

Gem::Specification.new do |s|
  s.name        = "backchatio-hookup"
  s.version     = Backchat::Hookup::VERSION
  s.platform    = Gem::Platform::RUBY
  s.authors     = ["This gem provides reliable websocket client."]
  s.email       = ["ivan@backchat.io"]
  s.homepage    = ""
  s.summary     = %q{Gem with BackChat WebSocket client}
  s.description = %q{Gem to with a client for use with the backchat websocket server.}

  s.rubyforge_project = "backchatio-hookup"

  s.files         = `git ls-files`.split("\n")
  s.test_files    = `git ls-files -- {test,spec,features}/*`.split("\n")
  s.executables   = `git ls-files -- bin/*`.split("\n").map{ |f| File.basename(f) }
  s.require_paths = ["lib"]
  
  s.add_dependency("eventmachine")
  s.add_dependency("faye-websocket")
  s.add_dependency("json")
  
  s.add_development_dependency('webmock')
  s.add_development_dependency "rack"
  s.add_development_dependency "rspec", "~> 2.8.0"
  s.add_development_dependency "rainbows", ">= 1.0.0"
  s.add_development_dependency "em-spec", ">= 0.2.6"
end
