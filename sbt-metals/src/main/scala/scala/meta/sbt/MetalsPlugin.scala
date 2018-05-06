package scala.meta.sbt {

  import sbt._
  import sbt.Keys._
  import java.io._

  object MetalsPlugin extends AutoPlugin {
    override def trigger = allRequirements
    override def requires = sbt.plugins.JvmPlugin && org.ensime.EnsimePlugin

    object autoImport
    import autoImport._

    def scala211 = "2.11.12"

    def scala212 = "2.12.4"

    def supportedScalaVersions = List(scala212, scala211)

    def semanticdbVersion = "2.1.7"

    val metalsWriteBuildInfo = inputKey[Unit](
      "Write build information to .metals/buildinfo/"
    )

    def semanticdbScalac =
      "org.scalameta" % "semanticdb-scalac" % semanticdbVersion cross CrossVersion.full

    def buildinfoDir: Def.Initialize[File] = Def.setting {
      baseDirectory.in(ThisBuild).value / ".metals" / "buildinfo"
    }

    override def globalSettings = Seq(
      commands ++= Seq(
        semanticdbEnable,
        metalsSetup
      )
    )

    override def projectSettings = Seq(
      metalsWriteBuildInfo := metalsWriteBuildInfoTask.evaluated,
      aggregate in metalsWriteBuildInfo := false
    )

    def metalsWriteBuildInfoTask: Def.Initialize[InputTask[Unit]] =
      Def.inputTask {
        val config = org.ensime.EnsimePlugin.ensimeConfigTask.evaluated

        // FIXME write out the EnsimeConfig ADT to .property files

        ???
      }

    lazy val metalsSetup = Command.command(
      "metalsSetup",
      briefHelp =
        "Generates .metals/buildinfo/**.properties files containing build metadata " +
          "such as classpath and source directories.",
      detail = ""
    ) { st: State =>
      "semanticdbEnable" :: "metalsWriteBuildInfo" :: st
    }

    /** sbt 1.0 and 0.13 compatible implementation of partialVersion */
    private def partialVersion(version: String): Option[(Long, Long)] =
      CrossVersion.partialVersion(version).map {
        case (a, b) => (a.toLong, b.toLong)
      }

    private lazy val partialToFullScalaVersion: Map[(Long, Long), String] =
      (for {
        v <- supportedScalaVersions
        p <- partialVersion(v).toList
      } yield p -> v).toMap

    private def projectsWithMatchingScalaVersion(
        state: State
    ): Seq[(ProjectRef, String)] = {
      val extracted = Project.extract(state)
      for {
        p <- extracted.structure.allProjectRefs
        version <- scalaVersion.in(p).get(extracted.structure.data).toList
        partialVersion <- partialVersion(version).toList
        fullVersion <- partialToFullScalaVersion.get(partialVersion).toList
      } yield p -> fullVersion
    }

    /** Command to automatically enable semanticdb-scalac for shell session */
    lazy val semanticdbEnable = Command.command(
      "semanticdbEnable",
      briefHelp =
        "Configure libraryDependencies, scalaVersion and scalacOptions for scalafix.",
      detail =
        """1. enables the semanticdb-scalac compiler plugin
          |2. sets scalaVersion to latest Scala version supported by scalafix
          |3. add -Yrangepos to scalacOptions""".stripMargin
    ) { s =>
      val extracted = Project.extract(s)
      val settings: Seq[Setting[_]] = for {
        (p, fullVersion) <- projectsWithMatchingScalaVersion(s)
        isEnabled = libraryDependencies
          .in(p)
          .get(extracted.structure.data)
          .exists(_.exists(_.name == "semanticdb-scalac"))
        if !isEnabled
        setting <- List(
          scalaVersion.in(p) := fullVersion,
          scalacOptions.in(p) ++= List(
            "-Yrangepos",
            s"-Xplugin-require:semanticdb"
          ),
          libraryDependencies.in(p) += compilerPlugin(
            "org.scalameta" % "semanticdb-scalac" %
              semanticdbVersion cross CrossVersion.full
          )
        )
      } yield setting
      val semanticdbInstalled = Compat.appendWithSession(extracted, settings, s)
      s.log.info("👌 semanticdb-scalac is enabled")
      semanticdbInstalled
    }
  }
}

package sbt {
  package internal {
    // This package doesn't exist in sbt 0.13
  }

  // We need `Load`, which is `sbt.Load` in 0.13, `sbt.internal.Load` in 1.0.
  import internal._
  object Compat {

    // Copy-pasted `appendWithSession` from sbt, available only in sbt 1.1.
    def appendWithSession(
        extracted: Extracted,
        settings: Seq[Setting[_]],
        state: State
    ): State =
      appendImpl(extracted, settings, state, extracted.session.mergeSettings)

    private[this] def appendImpl(
        extracted: Extracted,
        settings: Seq[Setting[_]],
        state: State,
        sessionSettings: Seq[Setting[_]]
    ): State = {
      import extracted.{currentRef, rootProject, session, showKey, structure}
      val appendSettings =
        Load.transformSettings(
          Load.projectScope(currentRef),
          currentRef.build,
          rootProject,
          settings
        )
      val newStructure =
        Load.reapply(sessionSettings ++ appendSettings, structure)
      Project.setProject(session, newStructure, state)
    }
  }
}
