import sbt._
import Keys._
import xml.Group

object HookupBuild extends Build {
  
  val projectSettings = Defaults.defaultSettings ++ Seq(
    organization := "io.backchat.hookup",
    name := "hookup",
    version := "0.2.3",
    scalaVersion := "2.10.2",
    //crossScalaVersions := Seq("2.9.1", "2.9.1-1", "2.9.2"),
    compileOrder := CompileOrder.ScalaThenJava,
    libraryDependencies ++= Seq(
      "io.netty" % "netty" % "3.6.6.Final",
      "com.github.nscala-time" %% "nscala-time" % "0.4.2",
      "org.json4s" %% "json4s-jackson" % "3.2.4" % "compile",
      "commons-io" % "commons-io" % "2.4",
      "com.typesafe.akka" %% "akka-actor" % "2.1.4" % "compile",
      "com.typesafe.akka" %% "akka-testkit" % "2.1.4" % "test",
      "org.specs2" %% "specs2" % "1.14" % "test",
      "junit" % "junit" % "4.11" % "test",
      "joda-time" % "joda-time" % "2.2"
    ),
    scalacOptions ++= Seq(
      "-optimize",
      "-deprecation",
      "-unchecked",
      "-Xcheckinit",
      "-encoding", "utf8"),
    parallelExecution in Test := false,
    testOptions := Seq(Tests.Argument("console", "junitxml")),
    testOptions <+= (crossTarget, resourceDirectory in Test) map { (ct, rt) =>
      Tests.Setup { () =>
        System.setProperty("specs2.junit.outDir", new File(ct, "specs-reports").getAbsolutePath)
        System.setProperty("java.util.logging.config.file", new File(rt, "logging.properties").getAbsolutePath)
      }
    },
    javacOptions ++= Seq("-Xlint:unchecked", "-source", "1.6", "-target", "1.6"),
    scalacOptions ++= Seq("-language:implicitConversions")
  )

  lazy val root =
    Project("hookup", file("."), settings = projectSettings)



}