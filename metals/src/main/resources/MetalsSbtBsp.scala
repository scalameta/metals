package metals

import sbt._
import sbt.Keys._
import Project.inConfig
import sbt.internal.inc.ScalaInstance

object MetalsPlugin extends AutoPlugin {
  override def requires = plugins.JvmPlugin
  override def trigger = allRequirements

  val semanticdbVersion = "4.3.21"

  override lazy val projectSettings: Seq[Def.Setting[_]] = Seq(
    semanticdbCompilerPlugin := {
      ("org.scalameta" % "semanticdb-scalac" % semanticdbVersion)
        .cross(CrossVersion.full)
    },
    allDependencies ++= {
      val sv = scalaVersion.value
      if (!ScalaInstance.isDotty(sv)) {
        List(
          compilerPlugin(
            "org.scalameta" % s"semanticdb-scalac_${sv}" % semanticdbVersion
          )
        )
      } else Nil
    }
  ) ++ inConfig(Compile)(configurationSettings) ++ inConfig(Test)(
    configurationSettings
  )

  lazy val configurationSettings: Seq[Def.Setting[_]] = List(
    scalacOptions := {
      val old = scalacOptions.value
      val sdbOptions = semanticdbOptions.value
      val sv = scalaVersion.value
      (old.toVector ++ sdbOptions ++
        (if (ScalaInstance.isDotty(sv)) Some("-Ysemanticdb")
         else None)).distinct
    },
    semanticdbTargetRoot := {
      val in = semanticdbIncludeInJar.value
      if (in) classDirectory.value
      else semanticdbTargetRoot.value
    },
    semanticdbOptions ++= {
      val tr = semanticdbTargetRoot.value
      val sv = scalaVersion.value
      if (ScalaInstance.isDotty(sv)) List("-semanticdb-target", tr.toString)
      else
        List(
          s"-P:semanticdb:sourceroot:${baseDirectory.in(ThisBuild).value}",
          s"-P:semanticdb:targetroot:$tr",
          "-Yrangepos",
          //test
          // Needed for "find references" on implicits and `apply` methods.
          s"-P:semanticdb:synthetics:on",
          // Don't fail compilation in case of Scalameta crash during SemanticDB generation.
          s"-P:semanticdb:failures:warning"
        )
    }
  )
}
