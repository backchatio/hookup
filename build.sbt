import xml.Group
// import scalariform.formatter.preferences._

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

seq(buildInfoSettings: _*)

sourceGenerators in Compile <+= buildInfo

buildInfoKeys := Seq[sbtbuildinfo.Plugin.BuildInfoKey.Entry[_]](name, version, scalaVersion, sbtVersion)
// buildInfoKeys := Seq[Scoped](name, version, scalaVersion, sbtVersion)

buildInfoPackage <<= organization
