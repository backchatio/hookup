
module Backchat
  module WebSocket
    class WireFormat

      def parse_message(message)
        if message.is_a?(String)
          begin
            if possibly_json?(message) 
              json = JSON.parse(message)
              full = json
              unless json.key?("content") || json.key?("type") 
                full = {"type" => "json", "content" => json }
              else
                full["type"] ||= "json"
              end
              full["content"] = parse_message(full["content"]) if full["type"] == "ack_request"
              full
            else
              { "type" => "text", "content" => message }
            end
          rescue
            { "type" => "text", "content" => message }
          end
        else 
          message
        end
      end

      def render_message(message)
        JSON.generate(build_message(message))
      end

      def build_message(message)
        unless message.is_a?(String)
          built = parse_message(message)
          built["content"] = parse_message(built["content"]) if built["type"] == "ack_request"
          built 
        else
          { "type" => "text", "content" => message }
        end
      end

      def unwrap_content(message)
        parsed = parse_message(message)
        return parsed["content"]["content"] if parsed["type"] == "ack_request"          
        if (parsed["type"] == "json") 
          parsed.delete('type')
          return parsed.size == 1 && parsed["content"] ? parsed["content"] : parsed
        end
        return parsed if parsed["type"] == "ack" || parsed["type"] === "needs_ack"           
        parsed["content"]
      end

      private
      def possibly_json?(message)
        message =~ /^(?:\{|\[)/
      end
    end
  end
end