import sbt._
import Keys._

object VersionGenPlugin extends Plugin {
    object VersionGenKeys {
      val versionGen     = TaskKey[Seq[File]]("version-gen")
      val versionGenPackage  = SettingKey[String]("version-gen-package")
      val versionGenClass  = SettingKey[String]("version-gen-class")
    }
    import VersionGenKeys._

    lazy val allSettings: Seq[Setting[_]]  = Seq(
      versionGenPackage  <<= Keys.organization,
      versionGenClass  := "Version",
      versionGen     <<= (sourceManaged in Compile, name, version, versionGenPackage, versionGenClass) map {
        (sourceManaged:File, name:String, version:String, vgp:String, vgc:String) =>
          val file  = sourceManaged / vgp.replace(".","/") / ("%s.scala" format vgc)
          val code  =
                  (if (vgp != null && vgp.trim.nonEmpty)  "package " + vgp + "\n" else "") +
                  "object " + vgc + " {\n" +
                  "  val name\t= \"" + vgc + "\"\n" +
                  "  val version\t= \"" + version + "\"\n" +
                  "}\n"
          IO write (file, code)
          Seq(file)
      },
      sourceGenerators in Compile <+= versionGen map identity
    )
}
