import sbt._
import sbt.Keys._
import java.io._

object MetalsPlugin extends AutoPlugin {
  override def trigger = allRequirements
  override def requires = sbt.plugins.JvmPlugin
  val metalsCompilerConfig =
    taskKey[String]("Configuration parameters for autocompletion.")
  val metalsSetup =
    taskKey[Unit](
      "Generate build metadata for completions and indexing dependency sources"
    )
  override lazy val globalSettings = List(
    commands += SemanticdbEnable.command,
    // `*:metalsSetupCompletions` sets up all configuration in all projects (note *: prefix, that's needed!)
    metalsSetup := Def.taskDyn {
      val filter = ScopeFilter(inAnyProject, inConfigurations(Compile, Test))
      metalsSetup.all(filter)
    }.value
  )
  override lazy val projectSettings = List(Compile, Test).flatMap { c =>
    inConfig(c)(
      Seq(
        metalsCompilerConfig := {
          val props = new java.util.Properties()
          props.setProperty(
            "sources",
            sources.value.distinct.mkString(File.pathSeparator)
          )
          props.setProperty(
            "unmanagedSourceDirectories",
            unmanagedSourceDirectories.value.distinct
              .mkString(File.pathSeparator)
          )
          props.setProperty(
            "managedSourceDirectories",
            managedSourceDirectories.value.distinct
              .mkString(File.pathSeparator)
          )
          props.setProperty(
            "scalacOptions",
            scalacOptions.value.mkString(" ")
          )
          props.setProperty(
            "classDirectory",
            classDirectory.value.getAbsolutePath
          )
          props.setProperty(
            "dependencyClasspath",
            dependencyClasspath.value
              .map(_.data.toString)
              .mkString(File.pathSeparator)
          )
          val sourceJars = for {
            configurationReport <- updateClassifiers.value.configurations
            moduleReport <- configurationReport.modules
            (artifact, file) <- moduleReport.artifacts
            if artifact.classifier.exists(_ == "sources")
          } yield file
          props.setProperty(
            "sourceJars",
            sourceJars.mkString(File.pathSeparator)
          )
          val out = new ByteArrayOutputStream()
          props.store(out, null)
          out.toString()
        },
        metalsSetup := {
          val f = target.value / (c.name + ".compilerconfig")
          IO.write(f, metalsCompilerConfig.value)
          streams.value.log.info(
            "Wrote metals configuration to: " + f.getAbsolutePath
          )
        }
      )
    )
  }
}

/** Command to automatically enable semanticdb-scalac for shell session */
object SemanticdbEnable {

  /** sbt 1.0 and 0.13 compatible implementation of partialVersion */
  private def partialVersion(version: String): Option[(Long, Long)] =
    CrossVersion.partialVersion(version).map {
      case (a, b) => (a.toLong, b.toLong)
    }

  private val supportedScalaVersions = List("2.12.4", "2.11.12")
  private val semanticdbVersion = "2.1.5"

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
