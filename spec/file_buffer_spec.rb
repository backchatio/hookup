# -*- encoding: utf-8 -*-
require File.expand_path("../spec_helper", __FILE__)
require 'fileutils'

describe Backchat::Hookup::FileBuffer do
  
  context "when opening" do
    work_path = "./rb-test-work"
    log_path = "./rb-test-work/testing/and/such/buffer.log"

    before(:each) do 
      FileUtils.rm_rf(work_path) if File.exist?(work_path)
    end

    it "creates the path if missing" do
      buff = FileBuffer.new(work_path)
      buff.on(:open) do
        File.exist?(work_path).should be_true
      end
      buff.open
      FileUtils.rm_rf(work_path)
    end
  end

  context 'when not draining' do
    work_path = "./rb-test-work2"
    log_path = "#{work_path}/buffer.log"
    exp1 = "the first message"
    exp2 = "the second message"

    before(:each) do
      FileUtils.rm_rf(work_path) if File.exist?(work_path)
    end

    after(:each) do 
      FileUtils.rm_rf(work_path)
    end

    it "writes to a file" do      
      buff = FileBuffer.new(log_path)
      lines = []
      buff.open
      buff.write(exp1)
      buff.write(exp2)
      buff.close
      File.open(log_path).readlines.size.should == 2      
    end
  end 

  context "when draining" do
    work_path = "./rb-test-work3"
    log_path = "#{work_path}/buffer.log"
    exp1 = "the first message"
    exp2 = "the second message"

    before(:each) do
      FileUtils.rm_rf(work_path) if File.exist?(work_path)
    end

    after(:each) do 
      FileUtils.rm_rf(work_path)
    end

    it "writes new sends to the memory buffer" do
      mem = []
      buff = FileBuffer.new(log_path, :memory_buffer => mem, :initial_state => 1)
      buff.write(exp1)
      buff.write(exp2)
      buff.close
      mem.size.should == 2     
    end

    it "raises data events for every line in the buffer" do
      lines = []
      buff = FileBuffer.new(log_path)
      buff.on(:data) do |args|
        lines << args
      end
      buff.open
      buff.write(exp1)
      buff.write(exp2) do
        buff.drain
      end
      buff.close
      lines.size.should == 2
    end
  end
end