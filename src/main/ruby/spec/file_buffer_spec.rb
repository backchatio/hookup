# -*- encoding: utf-8 -*-
require File.expand_path("../spec_helper", __FILE__)
require 'fileutils'

describe Backchat::WebSocket::FileBuffer do 
  
  context "when opening" do
    work_path = "./rb-test-work"
    log_path = "./rb-test-work/testing/and/such/buffer.log"

    before(:each) do 
      FileUtils.rm_rf(work_path) if File.exist?(work_path)
    end

    it "creates the path if missing" do
    end
  end

  context 'when not draining' do
    it "writes to a file" do
    end
  end 

  context "when draining" do
    it "writes new sends to the memory buffer" do
      
    end

    it "raises data events for every line in the buffer" do
      
    end
  end
end