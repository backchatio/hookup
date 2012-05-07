resolvers += Resolver.url("sbt-plugin-releases",
  new URL("http://scalasbt.artifactoryonline.com/scalasbt/sbt-plugin-releases/"))(
    Resolver.ivyStylePatterns)


//addSbtPlugin("com.typesafe.sbtscalariform" % "sbtscalariform" % "0.3.1")

// addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.1.1")

//addSbtPlugin("com.jsuereth" % "xsbt-gpg-plugin" % "0.6")


libraryDependencies <+= (scalaVersion) { sv => ("com.typesafe.sbtscalariform" % "sbtscalariform" % "0.3.1").extra(CustomPomParser.SbtVersionKey -> "0.11.2", CustomPomParser.ScalaVersionKey -> sv).copy(crossVersion = false) }

libraryDependencies <+= (scalaVersion) { sv => ("com.eed3si9n" % "sbt-buildinfo" % "0.1.1").extra(CustomPomParser.SbtVersionKey -> "0.11.2", CustomPomParser.ScalaVersionKey -> sv).copy(crossVersion = false) }

libraryDependencies <+= (scalaVersion) { sv => ("com.jsuereth" % "xsbt-gpg-plugin" % "0.6").extra(CustomPomParser.SbtVersionKey -> "0.11.2", CustomPomParser.ScalaVersionKey -> sv).copy(crossVersion = false) }
