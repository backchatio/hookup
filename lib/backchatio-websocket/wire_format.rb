
module Backchat
  module WebSocket
    class WireFormat

      def parse_message(message)
        return message unless message.is_a?(String)
        return { "type" => "text", "content" => message } unless possibly_json?(message)
        begin
          json = JSON.parse(message)
          return json if json.is_a?(Hash) && (json["type"] == "ack" || json["type"] == "needs_ack")
          return json.update("content" => parse_message(json["content"])) if json.is_a?(Hash) && json["type"] == "ack_request"
          { "type" => "json", "content" => json }
        rescue Exception => e
          puts e
          { "type" => "text", "content" => message }
        end
      end

      def render_message(message)
        JSON.generate(build_message(message))
      end

      def build_message(message)
        return { "type" => "text", "content" => message } if message.is_a?(String)
        prsd = parse_message(message)
        return prsd if prsd.is_a?(Hash) && prsd["type"] == "ack"
        prsd["content"] = parse_message(prsd["content"]) if prsd.is_a?(Hash) && prsd["type"] == "ack_request"
        if prsd.is_a?(Hash)
          built = prsd.key?("content") ? prsd : { "type" => "json", "content" => prsd}
          built["type"] ||= "json"
          built
        else
          {"type" => "json", "content" => prsd}
        end
      end

      def unwrap_content(message)
        parsed = parse_message(message)
        return parsed["content"]["content"] if parsed["type"] == "ack_request"          
        if (parsed["type"] == "json") 
          parsed.delete('type')
          return parsed.size == 1 && parsed.key?("content") ? parsed["content"] : parsed
        end
        return parsed if parsed["type"] == "ack" || parsed["type"] == "needs_ack"           
        parsed["content"]
      end

      private
      def possibly_json?(message)
        !!(message =~ /^(?:\{|\[)/)
      end
    end
  end
end