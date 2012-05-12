# -*- encoding: utf-8 -*-

require 'time'
require "addressable/uri"
require 'json'
require 'json/ext'
require 'backchatio-hookup/version'
require 'backchatio-hookup/errors'

module Backchat
  module Hookup
    autoload :Client, "#{File.dirname(__FILE__)}/backchatio-hookup/client"
    autoload :WireFormat, "#{File.dirname(__FILE__)}/backchatio-hookup/wire_format"
    autoload :FileBuffer, "#{File.dirname(__FILE__)}/backchatio-hookup/file_buffer"
  end
end