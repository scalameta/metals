import sbt._
import sbt.Keys._
import sbtbuildinfo.BuildInfoKey
import sbtbuildinfo.BuildInfoKeys.{buildInfoKeys, buildInfoPackage}

object JavaPcSettings {
  lazy val currentJavaHome = settingKey[File]("current java home")
  lazy val currentJavaVersion = settingKey[String]("current java version")

  def settings(sharedSettings: Def.SettingsDefinition): Project => Project = {
    prj: Project =>
      prj.settings(
        sharedSettings,
        moduleName := "mtags-java",
        scalaVersion := V.scala3,
        currentJavaHome := file(System.getProperty("java.home")),
        currentJavaVersion := {
          val version = System.getProperty("java.version")

          if (version.startsWith("1.")) version.substring(2, 3)
          else version.takeWhile(_ != '.')
        },
        Compile / unmanagedJars ++= {
          if (currentJavaVersion.value == "8")
            Seq(
              file(
                currentJavaHome.value.getPath
                  .stripSuffix("jre") + "lib/tools.jar"
              )
            )
          else Nil
        },
      )
  }
}
