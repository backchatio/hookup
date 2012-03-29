require "spec_helper"

describe EM::RSpec::AsyncSteps do
  StepModule = EM::RSpec.async_steps do
    def multiply(x, y, &resume)
      EM.add_timer(0.1) do
        @result = x * y
        resume.call
      end
    end
    
    def subtract(n, &resume)
      EM.add_timer(0.1) do
        @result -= n
        resume.call
      end
    end
    
    def check_result(n, &resume)
      @result.should == n
      @checked = true
      resume.call
    end
  end
  
  before do
    @steps = Class.new { include StepModule }.new
  end
  
  def result
    @steps.instance_eval { @result }
  end
  
  describe :sync do
    describe "with no steps pending" do
      it "does not block" do
        @steps.sync
        result.should == nil
      end
    end
    
    describe "with a pending step" do
      before { @steps.multiply 7, 8 }
      
      it "waits for the step to complete" do
        result.should == nil
        @steps.sync
        result.should == 56
      end
    end
    
    describe "with many pending steps" do
      before do
        @steps.multiply 7, 8
        @steps.subtract 5
      end
      
      it "waits for all the steps to complete" do
        result.should == nil
        @steps.sync
        result.should == 51
      end
    end
    
    describe "with FakeClock activated" do
      include EM::RSpec::FakeClock
      before { clock.stub }
      after { @steps.sync && clock.reset }
      
      it "waits for all the steps to complete" do
        @steps.instance_eval { @result = 11 }
        @steps.check_result(11)
        @steps.sync
        @steps.instance_eval { @checked }.should == true
      end
    end
  end
  
  describe "RSpec example" do
    include StepModule
    
    it "passes" do
      multiply 6, 3
      subtract 7
      check_result 11
    end
    
    # This should fail so we know RSpec reports async errors
    it "fails" do
      multiply 9, 4
      check_result 5
    end
  end
end

