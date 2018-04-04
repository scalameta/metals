package scala.meta.sbt.metals

import sbt._
import sbt.Keys._

object MetalsPlugin extends AutoPlugin {
  override def trigger = allRequirements
  override def requires = sbt.plugins.JvmPlugin
  object autoImport {

    val metalsBuildinfo =
      taskKey[Seq[(String, String)]](
        "Build metadata for completions and indexing dependency sources"
      )
    val metalsWriteBuildinfo =
      taskKey[Unit](
        "Write build metadata to .metals/buildinfo/"
      )

    lazy val semanticdbSettings = Seq(
      addCompilerPlugin(
        "org.scalameta" % "semanticdb-scalac" % Metals.semanticdbVersion cross CrossVersion.full
      ),
      scalacOptions += "-Yrangepos"
    )

    def metalsSettings(cs: Configuration*): Seq[Def.Setting[_]] = {
      val configs = if (cs.nonEmpty) cs else Seq(Compile)
      configs.flatMap { config =>
        inConfig(config)(
          Seq(
            metalsBuildinfo := Metals.metalsBuildinfoTask.value,
            metalsWriteBuildinfo := Metals.metalsWriteBuildinfoTask.value,
          )
        )
      } ++ Seq(
        // without config scope it will aggregate over all project dependencies
        // and their configurations
        metalsWriteBuildinfo := Def.taskDyn {
          val depsAndConfigs = ScopeFilter(
            inDependencies(ThisProject),
            inConfigurations(configs: _*)
          )
          metalsWriteBuildinfo.all(depsAndConfigs)
        }.value
      )
    }
  }
  import autoImport._

  override def projectSettings =
    metalsSettings(Compile, Test)

  override def globalSettings = Seq(
    commands ++= Seq(
      Metals.semanticdbEnable,
      Metals.metalsSetup,
    ),
    // without project scope it will aggregate over all projects
    metalsWriteBuildinfo := {
      metalsWriteBuildinfo.all(ScopeFilter(inAnyProject)).value
      streams.value.log.info("Metals rocks! 🤘")
    }
  )
}
