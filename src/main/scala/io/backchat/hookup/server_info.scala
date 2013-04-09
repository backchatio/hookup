package io.backchat.hookup

import akka.util.Timeout
import java.io.{FileInputStream, FileNotFoundException, File}
import com.typesafe.config.Config
import scala.concurrent.ExecutionContext
import javax.net.ssl.{KeyManagerFactory, SSLContext}
import java.security.KeyStore
import collection.mutable.ListBuffer

/**
 * A marker trait to indicate something is a a configuration for the server.
 */
trait ServerCapability

/**
 * Configuration for adding ssl support to the server
 *
 * @param keystorePath path to the keystore, by default it looks for the system property keystore.file.path
 * @param keystorePassword path to the keystore, by default it looks for the system property keystore.file.path
 * @param algorithm path to the keystore, by default it looks for the system property keystore.file.path
 */
case class SslSupport(
  keystorePath: String = sys.props("keystore.file.path"),
  keystorePassword: String = sys.props("keystore.file.password"),
  algorithm: String = sys.props.get("ssl.KeyManagerFactory.algorithm").flatMap(_.blankOption) | "SunX509") extends ServerCapability

/**
 * Configuration for content compression
 *
 * @param level the compression level
 */
case class ContentCompression(level: Int = 6) extends ServerCapability

/**
 * The subprotocols this server can respond to.
 *
 * @param protocol A supported protocol name
 * @param protocols remaining supported protocols
 */
case class SubProtocols(protocol: WireFormat, protocols: WireFormat*) extends ServerCapability

/**
 * The configuration for sending pings to a client. (Some websocket clients don't support ping frames)
 *
 * @param timeout the timeout for a connection to be idle before sending a ping.
 */
case class Ping(timeout: Timeout) extends ServerCapability

/**
 * The configuration for the flash policy xml
 *
 * @param domain the domain to accept connections for
 * @param ports the ports to accept connections on
 */
case class FlashPolicy(domain: String, ports: Seq[Int]) extends ServerCapability

/**
 * The maximum frame size for a websocket connection
 * @param size
 */
case class MaxFrameSize(size: Long = Long.MaxValue) extends ServerCapability

/**
 * The directory to use as a base directory for serving static files.
 * @param path The [[java.io.File]] to use as base path
 */
case class PublicDirectory(path: File) extends ServerCapability

/**
 * The file to use as favico.ico response.
 * If you use the public directory capabillity you can also place a file in the public directory
 * named favico.{ico,png,gif} and the static file server will serve the request.
 *
 * @param path The [[java.io.File]] to use as base path
 */
case class Favico(path: File) extends ServerCapability

/**
 * Private object used in unit tests
 */
private[hookup] case object RaiseAckEvents extends ServerCapability

/**
 * Private object used in unit tests
 */
private[hookup] case object RaisePingEvents extends ServerCapability

/**
 * @see [[io.backchat.hookup.ServerInfo]]
 */
object ServerInfo {
  /**
   * The default server name ''BackChat.io Hookup Server''
   */
  val DefaultServerName = "BackChat.io Hookup Server"

  /**
   * Creates a [[io.backchat.hookup.ServerInfo]] with the [[io.backchat.hookup.ServerInfo.DefaultServerName]]
   *
   * @param config A [[com.typesafe.config.Config]] object
   * @return the created [[io.backchat.hookup.ServerInfo]]
   */
  def apply(config: Config): ServerInfo = apply(config, DefaultServerName, DefaultProtocols)

  /**
   * Creates a [[io.backchat.hookup.ServerInfo]] with the [[io.backchat.hookup.ServerInfo.DefaultServerName]]
   *
   * @param config A [[com.typesafe.config.Config]] object
   * @param protocols A [[scala.collection.Map]] of string keys and wireformats with the supported formats
   * @return the created [[io.backchat.hookup.ServerInfo]]
   */
  def apply(config: Config, protocols: Seq[WireFormat]): ServerInfo =
    apply(config, DefaultServerName, protocols)

  /**
   * Creates a [[io.backchat.hookup.ServerInfo]]
   *
   * @param config A [[com.typesafe.config.Config]] object
   * @param name the name of the server
   * @return the created [[io.backchat.hookup.ServerInfo]]
   */
  def apply(config: Config, name: String): ServerInfo =
    apply(config, name, DefaultProtocols)

  /**
   * Creates a [[io.backchat.hookup.ServerInfo]]
   *
   * @param config A [[com.typesafe.config.Config]] object
   * @param name the name of the server
   * @param protocols A [[scala.collection.Map]] of string keys and wireformats with the supported formats
   * @return the created [[io.backchat.hookup.ServerInfo]]
   */
  def apply(config: Config, name: String, protocols: Seq[WireFormat]): ServerInfo = {
    import collection.JavaConverters._

    val allProtos = DefaultProtocols.filterNot(p => protocols.exists(_.name == p.name)) ++ protocols
    val caps = ListBuffer[ServerCapability]()
    if (config.hasPath("contentCompression"))
      caps += ContentCompression(config.getInt("contentCompression"))

    if (config.hasPath("subProtocols")) {
      val lst = config.getStringList("subProtocols").asScala.toList
      if (lst.nonEmpty)
        caps += SubProtocols(allProtos.head, allProtos.tail:_*)
    }

    if (config.hasPath("pingTimeout"))
      caps += Ping(Timeout(config.getMilliseconds("pingTimeout")))

    if (config.hasPath("flashPolicy")) {
      val domain = if (config.hasPath("flashPolicy.domain")) config.getString("flashPolicy.domain") else "*"
      val ports: List[Int] = if (config.hasPath("flashPolicy.ports")) config.getIntList("flashPolicy.ports").asScala.map(_.toInt).toList else {
        if (config.hasPath("flashPolicy.port")) List(config.getInt("flashPolicy.port")) else Nil
      }
      caps += FlashPolicy(domain, ports)
    }

    if (config.hasPath("publicDirectory")) {
      val path = new File(config.getString("publicDirectory"))
      if (path.exists())
        caps += PublicDirectory(path)
      else throw new FileNotFoundException(path.toString)
    }

    if (config.hasPath("favico")) {
      val path = new File(config.getString("favico"))
      if (path.exists())
        caps += Favico(path)
      else throw new FileNotFoundException(path.toString)
    }

    if (config.hasPath("ssl")) {
      val keystore = if (config.hasPath("ssl.keystore")) config.getString("ssl.keystore")
      else sys.props.get("keystore.file.path").getOrElse(throw new RuntimeException("You need to specify a keystore."))
      val passw = if (config.hasPath("ssl.password")) config.getString("ssl.password")
      else sys.props.get("keystore.file.password").getOrElse(throw new RuntimeException("You need to specify a password for the keystore"))
      val algo = if (config.hasPath("ssl.algorithm")) config.getString("ssl.algorithm")
      else (sys.props.get("ssl.KeyManagerFactory.algorithm").flatMap(_.blankOption) | "SunX509")
      caps += SslSupport(keystore, passw, algo)
    }

    caps += (if (config.hasPath("maxFrameSize")) MaxFrameSize(config.getBytes("maxFrameSize")) else MaxFrameSize())

    new ServerInfo(
      name,
      if (config.hasPath("version")) config.getString("version") else BuildInfo.version,
      if (config.hasPath("listenOn")) config.getString("listenOn") else "0.0.0.0",
      if (config.hasPath("port")) config.getInt("port") else 8765,
      if (config.hasPath("defaultProtocol")) config.getString("defaultProtocol") else DefaultProtocol,
      caps)
  }
}

/**
 * Main configuration object for a server
 *
 * @param name The name of this server, defaults to BackchatWebSocketServer
 * @param version The version of the server
 * @param listenOn Which address the server should listen on
 * @param port The port the server should listen on
 * @param defaultProtocol The default protocol for this server to use when no subprotocols have been specified
 * @param capabilities A sequence of [[io.backchat.hookup.ServerCapability]] configurations for this server
 */
/// code_ref: server_info
case class ServerInfo(
    name: String = ServerInfo.DefaultServerName,
    version: String = BuildInfo.version,
    listenOn: String = "0.0.0.0",
    port: Int = 8765,
    defaultProtocol: String = DefaultProtocol,
    capabilities: Seq[ServerCapability] = Seq.empty,
    executionContext: ExecutionContext = HookupClient.executionContext) {
/// end_code_ref
  /**
   * If the server should support SSL this will be filled with the ssl context to use
   */
  val sslContext: Option[SSLContext] = (capabilities collect {
    case cfg: SslSupport ⇒ {
      val ks = KeyStore.getInstance("JKS")
      val fin = new FileInputStream(new File(cfg.keystorePath).getAbsoluteFile)
      ks.load(fin, cfg.keystorePassword.toCharArray)
      val kmf = KeyManagerFactory.getInstance(cfg.algorithm)
      kmf.init(ks, cfg.keystorePassword.toCharArray)
      val context = SSLContext.getInstance("TLS")
      context.init(kmf.getKeyManagers, null, null)
      context
    }
  }).headOption

  /**
   * If the server should support content compression this will have the configuration for it.
   */
  val contentCompression: Option[ContentCompression] =
    (capabilities collect { case c: ContentCompression ⇒ c }).headOption

  /**
   * If the server should support sub-protocols this will have the configuration for it.
   */
  val protocols = DefaultProtocols ++ ((capabilities collect {
    case sp: SubProtocols => Seq(sp.protocol) ++ sp.protocols
  }).headOption getOrElse Seq.empty)

  val defaultWireFormat =
    protocols.find(_.name == defaultProtocol) getOrElse (throw new RuntimeException("Invalid default protocol."))

  /**
   * If the server should support pinging this will have the configuration for it.
   */
  val pingTimeout = (capabilities collect {
    case Ping(timeout) ⇒ timeout
  }).headOption

  /**
   * If the server should support max frame sizes for websocket frames this will have the configuration for it.
   */
  val maxFrameSize: Long = (capabilities collect {
    case MaxFrameSize(size) => size
  }).headOption | Long.MaxValue

  /**
   * The configuration for the flash policy, by default it allows all..
   */
  val flashPolicy = (capabilities collect {
    case FlashPolicy(domain, policyPorts) ⇒
      (<cross-domain-policy>
         <allow-access-from domain={ domain } to-ports={ policyPorts.mkString(",") }/>
       </cross-domain-policy>).toString()
  }).headOption getOrElse {
    (<cross-domain-policy>
       <allow-access-from domain="*" to-ports="*"/>
     </cross-domain-policy>).toString()
  }

//  def has[T: Manifest]: Boolean = capabilities exists { c => manifest[T].erasure isAssignableFrom c.getClass  }
}
