import xml.Group

publishMavenStyle := true

publishTo <<= version { (v: String) =>
  val nexus = "https://oss.sonatype.org/"
  if (v.trim.endsWith("SNAPSHOT"))
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else
    Some("releases"  at nexus + "service/local/staging/deploy/maven2")
}

publishArtifact in Test := false

pomIncludeRepository := { x => false }

packageOptions <<= (packageOptions, name, version, organization) map {
  (opts, title, version, vendor) =>
     opts :+ Package.ManifestAttributes(
      "Created-By" -> "Simple Build Tool",
      "Built-By" -> System.getProperty("user.name"),
      "Build-Jdk" -> System.getProperty("java.version"),
      "Specification-Title" -> title,
      "Specification-Vendor" -> "Mojolly Ltd.",
      "Specification-Version" -> version,
      "Implementation-Title" -> title,
      "Implementation-Version" -> version,
      "Implementation-Vendor-Id" -> vendor,
      "Implementation-Vendor" -> "Mojolly Ltd.",
      "Implementation-Url" -> "https://backchat.io"
     )
}

homepage := Some(url("https://backchat.io"))

startYear := Some(2012)

licenses := Seq(("MIT", url("http://github.com/backchat/scala-websocket/raw/HEAD/LICENSE")))

pomExtra <<= (pomExtra, name, description) {(pom, name, desc) => pom ++ Group(
  <scm>
    <connection>scm:git:git://github.com/backchat/scala-websocket.git</connection>
    <developerConnection>scm:git:git@github.com:backchat/scala-websocket.git</developerConnection>
    <url>https://github.com/backchat/scala-websocket.git</url>
  </scm>
  <developers>
    <developer>
      <id>casualjim</id>
      <name>Ivan Porto Carrero</name>
      <url>http://flanders.co.nz/</url>
    </developer>
  </developers>
)}