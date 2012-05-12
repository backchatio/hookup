# -*- encoding: utf-8 -*-

module Backchat
  module WebSocket
    class DefaultError < StandardError
    end

    class UriRequiredError < DefaultError
    end

    class InvalidURIError < DefaultError
    end

    class ServerDisconnectedError < DefaultError
    end
  end
end