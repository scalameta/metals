package scala.meta.sbt

import sbt._
import sbt.Keys._
import java.io._

object MetalsPlugin extends AutoPlugin {
  override def trigger = allRequirements
  override def requires = sbt.plugins.JvmPlugin
  object autoImport {

    val metalsCompilerConfig =
      taskKey[Seq[(String, String)]](
        "String containing build metadata in properties file format."
      )
    val metalsWriteCompilerConfig =
      taskKey[Unit](
        "Generate build metadata for completions and indexing dependency sources"
      )

    lazy val semanticdbSettings = Seq(
      addCompilerPlugin(
        "org.scalameta" % "semanticdb-scalac" % SemanticdbEnable.semanticdbVersion cross CrossVersion.full
      ),
      scalacOptions += "-Yrangepos"
    )

    def metalsSettings(configs: Configuration*): Seq[Def.Setting[_]] = {
      configs.flatMap { config =>
        inConfig(config)(
          Seq(
            metalsCompilerConfig := Seq(
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
              },
            ),
            metalsWriteCompilerConfig := {
              val props = new java.util.Properties()
              metalsCompilerConfig.value.foreach {
                case (k, v) => props.setProperty(k, v)
              }
              val out = new ByteArrayOutputStream()
              props.store(out, null)
              val filename = s"${config.name}.properties"
              val basedir = baseDirectory.in(ThisBuild).value /
                ".metals" / "buildinfo" / thisProject.value.id
              basedir.mkdirs()
              val outFile = basedir / filename
              IO.write(outFile, out.toString())
              streams.value.log.info("Created: " + outFile.getAbsolutePath)
            }
          )
        )
      } ++ Seq(
        // without config scope it will aggregate over all project dependencies
        // and their configurations
        metalsWriteCompilerConfig := {
          metalsWriteCompilerConfig.all(
            ScopeFilter(
              inDependencies(ThisProject),
              inConfigurations(configs: _*)
            )
          ).value
        }
      )
    }
  }
  import autoImport._

  override def globalSettings = Seq(
    commands += SemanticdbEnable.command,
    commands += Command.command(
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
        "Global / metalsWriteCompilerConfig" ::
        s
    },
    // without project scope it will aggregate over all projects
    metalsWriteCompilerConfig :=
      metalsWriteCompilerConfig.all(ScopeFilter(inAnyProject)).value
  )

  override def projectSettings =
    metalsSettings(Compile, Test)
}

/** Command to automatically enable semanticdb-scalac for shell session */
object SemanticdbEnable {

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

  lazy val command = Command.command(
    "semanticdbEnable",
    briefHelp =
      "Configure libraryDependencies, scalaVersion and scalacOptions for scalafix.",
    detail = """1. enables the semanticdb-scalac compiler plugin
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
    val semanticdbInstalled = extracted.append(settings, s)
    s.log.info("semanticdb-scalac installed 👌")
    semanticdbInstalled
  }
}
