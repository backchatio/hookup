import sbt._
import Keys._
import xml.Group

object BackchatWebSocketBuild extends Build {
  
  val projectSettings = Defaults.defaultSettings ++ Seq(
    organization := "io.backchat.websocket",
    name := "backchat-websocket",
    version := "0.2.0-SNAPSHOT",
    scalaVersion := "2.9.1",
    libraryDependencies ++= Seq(
      "io.netty" % "netty" % "3.4.1.Final",
      "net.liftweb" %% "lift-json" % "2.4" % "compile",
      "commons-io" % "commons-io" % "2.1",
      "com.typesafe.akka" % "akka-actor" % "2.0.1" % "compile",
      "com.typesafe.akka" % "akka-testkit" % "2.0.1" % "test",
      "org.specs2" %% "specs2" % "1.8.2" % "test",
      "junit" % "junit" % "4.10" % "test"
    ),
    resolvers += "Typesafe Releases" at "http://repo.typesafe.com/typesafe/releases",
    scalacOptions ++= Seq(
      "-optimize",
      "-deprecation",
      "-unchecked",
      "-Xcheckinit",
      "-encoding", "utf8"),
    parallelExecution in Test := false,
    testOptions := Seq(Tests.Argument("console", "junitxml")),
    testOptions <+= crossTarget map { ct =>
      Tests.Setup { () => System.setProperty("specs2.junit.outDir", new File(ct, "specs-reports").getAbsolutePath) }
    },
    javacOptions ++= Seq("-Xlint:unchecked"),
    externalResolvers <<= resolvers map { rs =>
      Resolver.withDefaultResolvers(rs, mavenCentral = true, scalaTools = false)
    })

  lazy val root =
    Project("backchat-websocket", file("."), settings = projectSettings)



}