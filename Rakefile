require 'bundler'
Bundler::GemHelper.install_tasks

task :default => [:test]

spec = Gem::Specification.load('backchatio-hookup.gemspec.gemspec')

Gem::PackageTask.new(spec) do |pkg|
end

require 'rspec/core/rake_task'
RSpec::Core::RakeTask.new(:test) do |spec|
  spec.pattern = 'spec/*_spec.rb'
  spec.rspec_opts = '--color --format doc'
end
