package metals

import scala.meta.internal.sbtmetals.BuildInfo

import sbt._
import sbt.Keys
import sbt.Keys._
import sbt.internal.inc.ScalaInstance
import sbt.plugins.SemanticdbPlugin

object MetalsPlugin extends AutoPlugin {
  override def requires = SemanticdbPlugin
  override def trigger = allRequirements

  override lazy val projectSettings: Seq[Def.Setting[_]] = Def.settings(
    Keys.semanticdbVersion := {
      if (requiresSemanticdb.value && !isScala3.value)
        BuildInfo.semanticdbVersion
      else Keys.semanticdbVersion.value
    },
    semanticdbEnabled := {
      semanticdbEnabled.value || requiresSemanticdb.value
    },
    semanticdbOptions ++= {
      if (isScala3.value || !requiresSemanticdb.value) Seq()
      else
        Seq(
          // Needed for "find references" on implicits and `apply` methods.
          "-P:semanticdb:synthetics:on",
          // Don't fail compilation in case of Scalameta crash during SemanticDB generation.
          "-P:semanticdb:failures:warning",
          s"-P:semanticdb:sourceroot:${(ThisBuild / baseDirectory).value}"
        )
    }
  )

  def requiresSemanticdb: Def.Initialize[Boolean] = Def.setting {
    bspEnabled.value &&
    (isScala3.value || BuildInfo.supportedScala2Versions.contains(
      scalaVersion.value
    ))
  }

  def isScala3: Def.Initialize[Boolean] = Def.setting {
    ScalaInstance.isDotty(scalaVersion.value)
  }
}
