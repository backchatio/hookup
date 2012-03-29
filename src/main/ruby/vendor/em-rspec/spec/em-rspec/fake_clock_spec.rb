require "spec_helper"

describe EM::RSpec::FakeClock do
  include EM::RSpec::FakeClock
  before { clock.stub }
  after { clock.reset }
  
  describe :add_timer do
    before do
      @calls = 0
      @timer = EM.add_timer(1) { @calls += 1 }
    end
    
    it "runs the timeout after the clock has ticked enough" do
      clock.tick 1
      @calls.should == 1
    end
    
    
    it "runs the timeout after time has accumulated" do
      clock.tick 0.5
      @calls.should == 0
      clock.tick 0.5
      @calls.should == 1
    end
    
    it "only runs the timeout once" do
      clock.tick 1.5
      @calls.should == 1
      clock.tick 1.5
      @calls.should == 1
    end
    
    it "does not run the callback if it is cleared" do
      EM.cancel_timer(@timer)
      clock.tick 1
      @calls.should == 0
    end
  end
  
  describe :add_periodic_timer do
    before do
      @calls = 0
      @timer = EM.add_periodic_timer(1) { @calls += 1 }
    end
    
    it "runs the timeout after clock has ticked enough" do
      clock.tick 1
      @calls.should == 1
    end
    
    it "runs the timeout after time has accumulated" do
      clock.tick 0.5
      @calls.should == 0
      clock.tick 0.5
      @calls.should == 1
    end
    
    it "runs the timeout repeatedly" do
      clock.tick 1.5
      @calls.should == 1
      clock.tick 1.5
      @calls.should == 3
    end
    
    it "does not run the callback if it is cleared" do
      EM.cancel_timer(@timer)
      clock.tick 1
      @calls.should == 0
    end
  end
  
  describe "with interleaved calls" do
    before do
      @calls = []
      EM.add_timer(5) do
        EM.add_timer(10) { @calls << :third }
        @calls << :first
      end
      
      EM.add_timer(5) { @calls << :second }
      
      EM.add_periodic_timer(4) { @calls << :ping }
    end
    
    it "schedules chains of functions correctly" do
      clock.tick 15
      @calls.should == [:ping, :first, :second, :ping, :ping, :third]
    end
  end
  
  describe "cancelling and resetting a timeout" do
    before do
      @calls = []
      @timer = EM.add_timer(1) { @calls << :done }
    end
    
    it "prolongs the delay before the timeout" do
      clock.tick 0.5
      EM.cancel_timer(@timer)
      EM.add_timer(1) { @calls << :done }
      clock.tick 0.5
      @calls.should == []
      clock.tick 0.5
      @calls.should == [:done]
    end
  end
  
  describe "Time.now" do
    before do
      @a = @b = nil
      EM.add_timer(1) { @b = Time.now }
      @a = Time.now
    end
    
    it "mirrors the fake time" do
      clock.tick 2
      (@b - @a).should == 1
    end
  end
end

