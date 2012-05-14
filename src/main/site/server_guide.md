---
layout: backchatio
title: BackChat.io hookup
---
## Hookup Servers

At this stage creating servers is only really supported in scala.  To work with servers there are a few concepts that need a bit more explanation.  We'll start with configuration of the server before moving on to what the server can do.

### Configuring the server.
At this stage the server is implemented with Netty and as such you can configure it to work with other netty modules.
We don't have an abstraction on top of Netty's request and response model and as such you can use it with any other server that is implemented with [Netty channel handlers](http://netty.io/docs/stable/api/org/jboss/netty/channel/SimpleChannelHandler.html).

To start a server with all the defaults you can use the following code: 

{% code_ref ../scala/io/backchat/hookup/examples/PrintingEchoServer.scala default_server %}

##### JSON formats
There are a few things that stand out in that code sample. There is a DefaultFormats on the first line which are lift-json formats. You can find more explanation about those in the [lift-json readme](https://github.com/lift/framework/tree/master/core/json#serialization) in the serialization section. The default json protocols use lift-json to parse and generate json structures.

##### Wire format
The wire format on the second line is a type-class/strategy for that knows how to serialize and deserialize messages for transport. Depending on which wireformat it may know about all the messages the websocket server knows about to enable the reliabillity features of the server, like message acking.  The [`JsonProtocolWireFormat`](http://backchatio.github.com/hookup/scaladoc/#io.backchat.hookup.JsonProtocolWireFormat) takes an implicit formats parameter which is provided by the JSON formats on the first line.

##### Creating a server
Once you've got the implicits in place to provide context you're ready to start a server. A [`HookupServer`](http://backchatio.github.com/hookup/scaladoc/#io.backchat.hookup.HookupServer) has 2 methods that allow you to register callbacks on either startup or shutdown. There are more things you can configure on a server than just the port. Take a look at the [`ServerInfo`]() class to find out all the properties.

{% code_ref ../scala/io/backchat/hookup/server.scala server_info %}

You can also configure the server from a typesafe config object, a full config looks something like this.

```
myServer {
  listenOn = "0.0.0.0"
  port = 8765
  pingTimeout = "50 seconds"
  contentCompression = 6
  subProtocols = ["jsonProtocol", "simpleJson"]
  ssl {
    keystore = "./ssl/keystore.jks"
    password = "changeme"
    algorithm = "SunX509"
  }
  flashPolicy {
    domain = "*.example.com"
    ports = [80, 443, 8080, 8843]
  }
  maxFrameSize = "32kb"
}
```

Some of the properties in this config will become more clear in the rest of this document.

To configure the modules that make up the default Hookup server we need to talk about [`ServerCapabilities`](http://backchatio.github.com/hookup/scaladoc/#io.backchat.hookup.ServerCapability). A server capability is available for you to extend yourself with your own modules if you want to use that form of configuration. If server capabilities don't allow the flexibillity you want then you can always subclass a [`HookupServer`](http://backchatio.github.com/hookup/scaladoc/#io.backchat.hookup.HookupServer) and make use of the template methods it provides. Let's start with the [`Ping`](http://backchatio.github.com/hookup/scaladoc/#io.backchat.hookup.Ping) capability.

#### Ping configuration

You can use the [`Ping`](http://backchatio.github.com/hookup/scaladoc/#io.backchat.hookup.Ping) configuration to configure whether the server should send out pings to clients when the connection has been idle for a while. The websocket protocol prescribes that the client is the one sending the pings but your use case may vary, you may need to appease a proxy in between. You configure the ping configuration with a [Timeout](http://doc.akka.io/api/akka/2.0.1/#akka.util.Timeout).

{% code_ref ../../test/scala/io/backchat/hookup/examples/ServerConfigurationsExample.scala server_with_ping %}

You can also configure compression for a server.

#### Compression configuration

By default the server doesn't use any content compression, but you can configure it to do so by adding the [`ContentCompression`](http://backchatio.github.com/hookup/scaladoc/#io.backchat.hookup.ContentCompression) capability. By default this capability is configured to a compression level of 6, this can of course be changed

{% code_ref ../../test/scala/io/backchat/hookup/examples/ServerConfigurationsExample.scala server_with_compression %}

You can also configure the max frame size of a server.

#### Max frame size

Netty requires you to set a maximum frame length for a websocket frame, by default it uses `Long.MaxValue` as max framesize, you can configure that to any number of bytes. The capability you need for this is the [`MaxFrameSize`](http://backchatio.github.com/hookup/scaladoc/#io.backchat.hookup.MaxFrameSize).

{% code_ref ../../test/scala/io/backchat/hookup/examples/ServerConfigurationsExample.scala server_with_max_frame %}

If you need SSL support for your server, basic support is included.

#### SSL configuration

For basic SSL support you can use the [`SslSupport`](http://backchatio.github.com/hookup/scaladoc/#io.backchat.hookup.SslSupport) capability. It uses the default truststore for your system but allows you to configure the keystore. The  default values are provided through the standard java system properties: `-Dkeystore.file.path=...`, `-Dkeystore.file.password=...`, `-Dssl.KeyManagerFactory.algorithm=SunX509`.

{% code_ref ../../test/scala/io/backchat/hookup/examples/ServerConfigurationsExample.scala server_with_ssl %}

The hookup server also has support for subprotocols.

#### Subprotocol configuration

The WebSocket spec provides a section on [subprotocols](http://tools.ietf.org/html/rfc6455#section-1.9), at this stage you can use those to limit the protocols a client can connect with. In the future they will be automatically configured by the registered wireformats on the server.

{% code_ref ../../test/scala/io/backchat/hookup/examples/ServerConfigurationsExample.scala server_with_subprotocols %}

The last of the default capabilities is the flash policy server.

#### Flash policy configuration

In certain browsers you may need to resort to using a [flash](https://github.com/gimite/web-socket-js) implementation of the websocket. To allow socket connections to your server flash requires you to run a [flash policy server](http://www.adobe.com/devnet/flashplayer/articles/socket_policy_files.html). We let it fallback to the port that serves the websocket. 
Leaving this configuration as default will allow all flash connections.

{% code_ref ../../test/scala/io/backchat/hookup/examples/ServerConfigurationsExample.scala server_with_flash_policy %}

This is the entire rundown of creating and configuring a server, when a server is started it's probably going to accept connections.

### Accepting connections

When a client connects, the server creates a [`HookupServerClient`](http://backchatio.github.com/hookup/scaladoc/#io.backchat.hookup.HookupServer$$HookupServerClient). You provide the factory when you create the server by creating a new instance of an implementation of a [`HookupServerClient`](http://backchatio.github.com/hookup/scaladoc/#io.backchat.hookup.HookupServer$$HookupServerClient). 

The [`HookupServerClient`](http://backchatio.github.com/hookup/scaladoc/#io.backchat.hookup.HookupServer$$HookupServerClient) allows you to keep state per connection, but you have to be aware that the client will potentially be accessed by multiple threads so you have to take care of synchronization (or use an actor for example).

##### Sending messages

Once connected a [`HookupServerClient`](http://backchatio.github.com/hookup/scaladoc/#io.backchat.hookup.HookupServer$$HookupServerClient) can send messages to the current connection or broadcast a message to all connected clients.
When you send a message you get an akka [`Future`](http://doc.akka.io/docs/akka/2.0.1/scala/futures.html) back, you can cancel this listen for an operation result. The operation result exists to work with many operations at the same time like in a broadcast you will receive failure and success status for every client connection you broadcasted to.  
There are 3 [operation results](http://backchatio.github.com/hookup/scaladoc/#io.backchat.hookup.OperationResult): [`Success`](http://backchatio.github.com/hookup/scaladoc/#io.backchat.hookup.Success$), [`Cancelled`](http://backchatio.github.com/hookup/scaladoc/#io.backchat.hookup.Cancelled$) and [`ResultList`](http://backchatio.github.com/hookup/scaladoc/#io.backchat.hookup.ResultList), the error case is handled in the `onFailure` method of the future.

A [`HookupServerClient`](http://backchatio.github.com/hookup/scaladoc/#io.backchat.hookup.HookupServer$$HookupServerClient) provides an alias for `send` as `!` and for `broadcast` as `><`.

##### Receiving messages

A [`HookupServerClient`](http://backchatio.github.com/hookup/scaladoc/#io.backchat.hookup.HookupServer$$HookupServerClient) requires you to implement one method or val a [`Hookup.Receive`](http://backchatio.github.com/hookup/scaladoc/#io.backchat.hookup.HookupClient$) partial function that handles [`InboundMessage`](http://backchatio.github.com/hookup/scaladoc/#io.backchat.hookup.InboundMessage) implementations and returns `Unit`. Below there's an example of a server client that prints all events it receives and echoes message events back.

{% code_ref ../scala/io/backchat/hookup/examples/PrintAllEventsServer.scala all_events %}
