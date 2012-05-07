# -*- encoding: utf-8 -*-
require File.expand_path("../spec_helper", __FILE__)
require "em-spec/rspec"

module ClientHelper 
  def server(port)
    begin
      @server = TestServer.new
      @server.listen(port)
      @port = port
    rescue Exception => e
      puts e
    end

    # EM.add_timer(0.1, &callback)
  end
  
  def stop_server(&callback)
    begin
      @server.stop      
    rescue Exception => e
      puts e
    ensure      
      EM.next_tick(&callback) if callback
    end
  end

end

describe Backchat::WebSocket::Client do 
  include ClientHelper
  include EM::SpecHelper
  

  default_timeout 1

  context "initializing" do

    before(:all) do
      @uri = "ws://localhost:2948/"
      @defaults_client = Client.new(:uri => @uri)
      @retries = 1..5
      @journaled = true
      @client = Client.new(:uri => @uri, :reconnect_schedule => @retries, :buffered => @journaled)
    end

    context "should raise when the uri param is" do
      it "missing" do
        (lambda do
          Client.new
        end).should raise_error(Backchat::WebSocket::UriRequiredError)
      end

      it "an invalid uri" do 
        (lambda do
          Client.new :uri => "http:"
        end).should raise_error(Backchat::WebSocket::InvalidURIError)
      end
    end

    it "should set use the default retry schedule" do
      @defaults_client.reconnect_schedule.should == Backchat::WebSocket::RECONNECT_SCHEDULE
    end

    it "should set journaling as default to false" do
      @defaults_client.should_not be_buffered
    end

    it "should use the uri from the options" do
      @defaults_client.uri.should == @uri
    end

    it "should use the retry schedule from the options" do
      @client.reconnect_schedule.should == @retries
    end

    it "should use the journaling value from the options" do 
      @client.should be_buffered
    end
  end

  context "sending json to the server" do 



    it "connects to the server" do
      em do
        server(8001)
        ws = Client.new("ws://127.0.0.1:8001/")
        op = false
        ws.on(:open) do 
          op = true
          ws.disconnect
        end
        ws.on(:close) do
          begin
            op.should be_true
            stop_server
          ensure
            done
          end
        end       
        ws.connect
      end
    end

    it "disconnects from the server" do
      em do
        server(8002)
        ws = Client.new("ws://127.0.0.1:8002/")
        ws.on(:open) do 
          ws.disconnect
        end
        ws.on(:close) do
          begin
            1.should == 1
            stop_server
          ensure
            done
          end
        end       
        ws.connect
      end
    end

    it "sends messages to the server" do
      msg = "I expect this to be echoed"
      em do
        server(8003)
        ws = Client.new("ws://127.0.0.1:8003/")
        ws.on(:open) do 
          ws.send msg
        end
        ws.on(:data) do |data|
          begin
            data.should == msg          
          ensure
            ws.disconnect
          end
        end
        ws.on(:close) do
          begin
            stop_server
          ensure
            done
          end
        end       
        ws.connect
      end
    end

  end

  # context "fault-tolerance" do 

  #   include ServerClientSteps

  #   before { server 8000 }
  #   after  { sync ; stop }

  #   it "recovers if the server comes back within the schedule" do
  #     connect("ws://0.0.0.0:8000/", [1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1]) 
  #     restart_server
  #     wait_for(5)
  #     check_connected
  #   end

  #   # it "raises a Backchat::Minutes::ServerDisconnectedError if the server doesn't come back" do
  #   #   connect("ws://0.0.0.0:8000/", [1, 1, 1, 1, 1, 1, 1]) 
  #   #   stop
  #   #     check_disconnected
  #   #     EM.add_timer(3) { check_connected }
  #   #   end
  #   # end

  # end

end