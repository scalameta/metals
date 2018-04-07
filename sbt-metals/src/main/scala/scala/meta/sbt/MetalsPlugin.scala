package scala.meta.sbt {

  import sbt._
  import sbt.Keys._
  import java.io._

  object MetalsPlugin extends AutoPlugin {
    override def trigger = allRequirements
    override def requires = sbt.plugins.JvmPlugin
    object autoImport {

      val metalsBuildInfo =
        taskKey[Map[String, String]](
          "List of key/value pairs for build information such as classpath/sourceDirectories"
        )
      val metalsWriteBuildInfo =
        taskKey[Unit](
          "Write build information to .metals/buildinfo/"
        )

      val semanticdbScalac =
        "org.scalameta" % "semanticdb-scalac" % Metals.semanticdbVersion cross CrossVersion.full

      lazy val semanticdbSettings = Seq(
        addCompilerPlugin(semanticdbScalac),
        scalacOptions += "-Yrangepos"
      )

      def metalsSettings(cs: Configuration*): Seq[Def.Setting[_]] = {
        val configs = if (cs.nonEmpty) cs else Seq(Compile)
        configs.flatMap { config =>
          inConfig(config)(
            Seq(
              metalsBuildInfo := Metals.metalsBuildInfoTask.value,
              metalsWriteBuildInfo := Metals.metalsWriteBuildInfoTask.value
            )
          )
        } ++ Seq(
          // without config scope it will aggregate over all project dependencies
          // and their configurations
          metalsWriteBuildInfo := Def.taskDyn {
            val depsAndConfigs = ScopeFilter(
              inDependencies(ThisProject),
              inConfigurations(configs: _*)
            )
            metalsWriteBuildInfo.all(depsAndConfigs)
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
        Metals.metalsSetup
      ),
      // without project scope it will aggregate over all projects
      metalsWriteBuildInfo := {
        metalsWriteBuildInfo.all(ScopeFilter(inAnyProject)).value
        streams.value.log.info("🤘 Metals is ready, time to rock!")
      }
    )
  }

  object Metals {

    def metalsBuildInfoTask: Def.Initialize[Task[Map[String, String]]] =
      Def.task {
        Map(
          "sources" -> sources.value.distinct.mkString(File.pathSeparator),
          "unmanagedSourceDirectories" ->
            unmanagedSourceDirectories.value.distinct
              .mkString(File.pathSeparator),
          "managedSourceDirectories" -> managedSourceDirectories.value.distinct
            .mkString(File.pathSeparator),
          "scalacOptions" -> scalacOptions.value.mkString(" "),
          "classDirectory" -> classDirectory.value.getAbsolutePath,
          "dependencyClasspath" -> dependencyClasspath.value
            .map(_.data.toString)
            .mkString(File.pathSeparator),
          "scalaVersion" -> scalaVersion.value,
          "sourceJars" -> {
            val sourceJars = for {
              configurationReport <- updateClassifiers.value.configurations
              moduleReport <- configurationReport.modules
              (artifact, file) <- moduleReport.artifacts
              if artifact.classifier.exists(_ == "sources")
            } yield file
            sourceJars.mkString(File.pathSeparator)
          }
        )
      }

    def metalsWriteBuildInfoTask: Def.Initialize[Task[Unit]] = Def.task {
      val props = new java.util.Properties()
      metalsBuildInfoTask.value.foreach {
        case (k, v) => props.setProperty(k, v)
      }
      val out = new ByteArrayOutputStream()
      props.store(out, null)
      val buildinfoDir = baseDirectory.in(ThisBuild).value /
        ".metals" / "buildinfo" / thisProject.value.id
      IO.delete(buildinfoDir)
      buildinfoDir.mkdirs()
      val outFile = buildinfoDir / s"${configuration.value.name}.properties"
      IO.write(outFile, out.toString())
      streams.value.log.info(s"Wrote ${outFile}")
    }

    lazy val metalsSetup = Command.command(
      "metalsSetup",
      briefHelp =
        "Generates .metals/buildinfo/**.properties files containing build metadata " +
          "such as classpath and source directories.",
      detail = ""
    ) { s =>
      val configDir = s.baseDir / ".metals" / "buildinfo"
      IO.delete(configDir)
      configDir.mkdirs()
      "semanticdbEnable" ::
        "*/metalsWriteBuildInfo" ::
        s
    }

    /** sbt 1.0 and 0.13 compatible implementation of partialVersion */
    private def partialVersion(version: String): Option[(Long, Long)] =
      CrossVersion.partialVersion(version).map {
        case (a, b) => (a.toLong, b.toLong)
      }

    val scala211 = "2.11.12"
    val scala212 = "2.12.4"
    val supportedScalaVersions = List(scala212, scala211)
    val semanticdbVersion = "2.1.7"

    lazy val partialToFullScalaVersion: Map[(Long, Long), String] = (for {
      v <- supportedScalaVersions
      p <- partialVersion(v).toList
    } yield p -> v).toMap

    def projectsWithMatchingScalaVersion(
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
