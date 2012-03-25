import sbt._
import Keys._
import xml.Group

object BackchatMinutesBuild extends Build {
  
  val projectSettings = Defaults.defaultSettings ++ Seq(
    organization := "io.backchat.websocket",
    name := "scala-websocket",
    version := "0.1.0",
    scalaVersion := "2.9.1",
    libraryDependencies ++= Seq(
      "org.specs2" %% "specs2" % "1.8.2" % "test",
      "junit" % "junit" % "4.10" % "test"
    ),
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
    (Project("scala-websocket", file("."), settings = projectSettings)
      settings (libraryDependencies ++= commonDeps, resolvers += "Twitter Maven" at "http://maven.twttr.com")
      settings (VersionGenPlugin.allSettings:_*))

  val commonDeps = Seq(
    "io.netty" % "netty" % "3.4.0.Alpha1",
    "net.liftweb" %% "lift-json" % "2.4",
    "com.twitter" %% "finagle-stream" % "3.0.0"
  )


}