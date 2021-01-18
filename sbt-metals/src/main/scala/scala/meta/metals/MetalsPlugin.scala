package metals

import scala.meta.internal.sbtmetals.BuildInfo

import sbt._
import sbt.Keys._
import sbt.internal.inc.ScalaInstance
import sbt.plugins.JvmPlugin

object MetalsPlugin extends AutoPlugin {
  override def requires = JvmPlugin
  override def trigger = allRequirements

  val supportedScala2Versions = BuildInfo.supportedScala2Versions.toList

  override lazy val globalSettings: Seq[Def.Setting[_]] = Seq(
    semanticdbVersion := BuildInfo.semanticdbVersion
  )

  override lazy val projectSettings: Seq[Def.Setting[_]] = Seq(
    semanticdbCompilerPlugin := {
      ("org.scalameta" % "semanticdb-scalac" % semanticdbVersion.value)
        .cross(CrossVersion.full)
    },
    allDependencies ++= {
      val versionOfScala = scalaVersion.value
      if (
        ScalaInstance.isDotty(versionOfScala) || !supportedScala2Versions
          .contains(versionOfScala)
      )
        Nil
      else
        List(
          compilerPlugin(
            "org.scalameta" % s"semanticdb-scalac_${versionOfScala}" % semanticdbVersion.value
          )
        )
    }
  ) ++ inConfig(Compile)(configurationSettings) ++ inConfig(Test)(
    configurationSettings
  )

  lazy val configurationSettings: Seq[Def.Setting[_]] = List(
    scalacOptions := {
      val versionOfScala = scalaVersion.value
      val old = scalacOptions.value
      if (!supportedScala2Versions.contains(versionOfScala)) {
        old
      } else {
        val sdbOptions = semanticdbOptions.value
        (old.toVector ++ sdbOptions ++
          (if (ScalaInstance.isDotty(versionOfScala)) {
             if (
               versionOfScala == "3.0.0-M1" || versionOfScala == "3.0.0-M2" || versionOfScala
                 .startsWith("0.")
             )
               Some("-Ysemanticdb")
             else
               Some("-Xsemanticdb")
           } else None)).distinct
      }
    },
    semanticdbEnabled := {
      val old = semanticdbEnabled.value
      if (ScalaInstance.isDotty(scalaVersion.value)) true
      else old
    },
    semanticdbTargetRoot := {
      val in = semanticdbIncludeInJar.value
      if (in) classDirectory.value
      else semanticdbTargetRoot.value
    },
    semanticdbOptions := {
      val old = semanticdbOptions.value
      val targetRoot = semanticdbTargetRoot.value
      val versionOfScala = scalaVersion.value
      if (
        ScalaInstance.isDotty(versionOfScala) || !supportedScala2Versions
          .contains(versionOfScala)
      )
        old
      else
        (Seq(
          s"-P:semanticdb:sourceroot:${baseDirectory.in(ThisBuild).value}",
          s"-P:semanticdb:targetroot:$targetRoot",
          "-Yrangepos",
          // Needed for "find references" on implicits and `apply` methods.
          s"-P:semanticdb:synthetics:on",
          // Don't fail compilation in case of Scalameta crash during SemanticDB generation.
          s"-P:semanticdb:failures:warning"
        ) ++ old).distinct
    }
  )
}
