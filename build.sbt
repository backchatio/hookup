import xml.Group
// import scalariform.formatter.preferences._
organization := "io.backchat.hookup"

name := "hookup"

version := "0.4.1"

scalaVersion := "2.10.5"

crossScalaVersions := Seq("2.10.5", "2.11.5")

compileOrder := CompileOrder.ScalaThenJava

libraryDependencies ++= Seq(
  "io.netty" % "netty" % "3.10.4.Final",
  "com.github.nscala-time" %% "nscala-time" % "1.4.0",
  "org.json4s" %% "json4s-jackson" % "3.2.10" % "compile",
  "commons-io" % "commons-io" % "2.4",
  "com.typesafe.akka" %% "akka-actor" % "2.1.4" % "compile",
  "com.typesafe.akka" %% "akka-testkit" % "2.1.4" % "test",
  "org.specs2" %% "specs2" % "1.14" % "test",
  "junit" % "junit" % "4.11" % "test",
  "joda-time" % "joda-time" % "2.2"
)

scalacOptions ++= Seq(
  "-optimize",
  "-deprecation",
  "-unchecked",
  "-Xcheckinit",
  "-encoding", "utf8")

parallelExecution in Test := false

testOptions := Seq(Tests.Argument("console", "junitxml"))

testOptions <+= (crossTarget, resourceDirectory in Test) map { (ct, rt) =>
  Tests.Setup { () =>
    System.setProperty("specs2.junit.outDir", new File(ct, "specs-reports").getAbsolutePath)
    System.setProperty("java.util.logging.config.file", new File(rt, "logging.properties").getAbsolutePath)
  }
}

scalacOptions ++= Seq("-language:implicitConversions")

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

homepage := Some(url("https://backchatio.github.com/hookup"))

startYear := Some(2012)

licenses := Seq(("MIT", url("http://github.com/backchatio/hookup/raw/HEAD/LICENSE")))

pomExtra <<= (pomExtra, name, description) {(pom, name, desc) => pom ++ Group(
  <scm>
    <connection>scm:git:git://github.com/backchatio/hookup.git</connection>
    <developerConnection>scm:git:git@github.com:backchatio/hookup.git</developerConnection>
    <url>https://github.com/backchatio/hookup.git</url>
  </scm>
  <developers>
    <developer>
      <id>casualjim</id>
      <name>Ivan Porto Carrero</name>
      <url>http://flanders.co.nz/</url>
    </developer>
  </developers>
)}

//seq(scalariformSettings: _*)
//
//ScalariformKeys.preferences :=
//  (FormattingPreferences()
//        setPreference(IndentSpaces, 2)
//        setPreference(AlignParameters, false)
//        setPreference(AlignSingleLineCaseStatements, true)
//        setPreference(DoubleIndentClassDeclaration, true)
//        setPreference(RewriteArrowSymbols, true)
//        setPreference(PreserveSpaceBeforeArguments, true)
//        setPreference(IndentWithTabs, false))
//
//(excludeFilter in ScalariformKeys.format) <<= excludeFilter(_ || "*Spec.scala")

// seq(buildInfoSettings: _*)

buildInfoSettings

sourceGenerators in Compile <+= buildInfo

buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion)

// buildInfoPackage := "io.backchat.hookup"

// buildInfoPackage := organization

buildInfoPackage <<= organization
