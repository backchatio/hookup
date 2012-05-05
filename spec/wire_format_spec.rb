# -*- encoding: utf-8 -*-
require File.expand_path("../spec_helper", __FILE__)

describe Backchat::WebSocket::WireFormat do 
  
  text = "this is a text message"
  text_result = { "type" => "text", "content" => "this is a text message" }
  json_result = { "content" => { "data" => "a json message" }, "type" => 'json'}
  json_data = {"data" => "a json message"}
  json = JSON.generate({"data" => "a json message"})
  array_result = { "content": [1, 2, 3, 4], "type": "json" }
  array_data = [1,2,3,4]
  array_json = [1,2,3,4].to_json
  ack_result = { "id" => 3, "type" => "ack" }
  ack = JSON.generate(ack_result)
  ack_request_result = { "id" => 3, "type" => "ack_request", "content" => { "type" => "text", "content" => "this is a text message" }}
  ack_request_data = { "id" => 3, "type" => "ack_request", "content" => "this is a text message" }
  ack_request = JSON.generate({ "id" => 3, "type" => "ack_request", "content" => { "type" => "text", "content" => "this is a text message" }})
  needs_ack_result = { "type" => "needs_ack", "timeout" => 5000 }
  needs_ack = JSON.generate({ "type" => "needs_ack", "timeout" => 5000 })

  wire_format = WireFormat.new

  context 'parses a message from' do
    it "a text message" do
      wire_format.parse_message(text).should == text_result
    end
    it "a json message" do
      wire_format.parse_message(json).should == json_result
    end
    it "an ack message" do
      wire_format.parse_message(ack).should == ack_result
    end
    it "an ack_request message" do
      wire_format.parse_message(ack_request).should == ack_request_result
    end
    it "a needs_ack message" do
      wire_format.parse_message(needs_ack).should == needs_ack_result
    end
  end 

  context "builds messages" do
    it "a text message" do
      wire_format.build_message(text).should == text_result
    end
    it "a json message" do
      wire_format.build_message(json_data).should == json_data
    end
    it "an ack message" do
      wire_format.build_message(ack_result).should == ack_result
    end
    it "an ack_request message" do
      wire_format.build_message(ack_request_data).should == ack_request_result
    end
  end

  context "unwraps messages" do
    it "a text message" do
      wire_format.unwrap_content(text_result).should == text
    end
    it "a json message" do
      wire_format.unwrap_content(json_result).should == json_data
    end
    it "an ack message" do
      wire_format.unwrap_content(ack_result).should == ack_result
    end
    it "an ack_request message" do
      wire_format.unwrap_content(ack_request_result).should == text
    end
    it "a needs_ack message" do
      wire_format.unwrap_content(needs_ack_result).should == needs_ack_result
    end
  end
end