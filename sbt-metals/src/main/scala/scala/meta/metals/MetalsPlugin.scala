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

  val semanticdbVersion = BuildInfo.semanticdbVersion

  override lazy val projectSettings: Seq[Def.Setting[_]] = Seq(
    semanticdbCompilerPlugin := {
      if (supportedScala2Versions.contains(scalaVersion.value))
        ("org.scalameta" % "semanticdb-scalac" % semanticdbVersion)
          .cross(CrossVersion.full)
      else {
        semanticdbCompilerPlugin.value
      }
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
            "org.scalameta" % s"semanticdb-scalac_${versionOfScala}" % semanticdbVersion
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
          s"-P:semanticdb:sourceroot:${(ThisBuild / baseDirectory).value}",
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
