package scala.meta.metals.maven

import java.{util => ju}

import scala.jdk.CollectionConverters._

import org.apache.maven.model.Plugin
import org.apache.maven.plugin.logging.Log
import org.apache.maven.project.MavenProject
import org.codehaus.plexus.util.xml.Xpp3Dom

private[maven] case class CompilerConfig(
    javacOptions: List[String],
    scalacOptions: List[String],
    scalaVersion: Option[String],
    annotationProcessorPaths: List[(String, String, String)] = Nil,
)

private[maven] object MavenCompilerConfig {

  import MavenPluginSupport._

  def extract(
      project: MavenProject,
      isTest: Boolean,
      log: Log = null,
  ): CompilerConfig = {
    val plugins = effectivePlugins(project)
    val props = project.getProperties

    def prop(key: String): Option[String] =
      Option(props.getProperty(key)).filter(_.nonEmpty)

    val javacConfig =
      Option(plugins.get(JavaCompilerPlugin)).flatMap(
        selectCompilerPluginConfiguration(_, isTest, Option(log))
      )

    val javacOpts = extractJavacOptions(javacConfig, prop, isTest)
    val (scalacOpts, scalaVer) = extractScalaOptions(plugins, isTest)
    val procPaths = extractAnnotationProcessorPaths(javacConfig, project)

    CompilerConfig(javacOpts, scalacOpts, scalaVer, procPaths)
  }

  private def extractJavacOptions(
      cfgOpt: Option[Xpp3Dom],
      prop: String => Option[String],
      isTest: Boolean,
  ): List[String] = {
    val args = List.newBuilder[String]

    val release =
      (if (isTest) prop("maven.compiler.testRelease") else None)
        .orElse(cfgOpt.flatMap(childText(_, "release")))
        .orElse(prop("maven.compiler.release"))
    val source =
      (if (isTest) prop("maven.compiler.testSource") else None)
        .orElse(cfgOpt.flatMap(childText(_, "source")))
        .orElse(prop("maven.compiler.source"))
    val target =
      (if (isTest) prop("maven.compiler.testTarget") else None)
        .orElse(cfgOpt.flatMap(childText(_, "target")))
        .orElse(prop("maven.compiler.target"))

    release match {
      case Some(v) => args += "--release" += v
      case None =>
        source.foreach(v => args += "-source" += v)
        target.foreach(v => args += "-target" += v)
    }

    val encoding =
      cfgOpt
        .flatMap(childText(_, "encoding"))
        .orElse(prop("project.build.sourceEncoding"))
        .orElse(prop("maven.compiler.encoding"))
    encoding.foreach(v => args += "-encoding" += v)

    val enablePreview =
      cfgOpt
        .flatMap(childText(_, "enablePreview"))
        .orElse(prop("air.compiler.enable-preview"))
    if (enablePreview.contains("true")) args += "--enable-preview"

    val parameters =
      cfgOpt
        .flatMap(childText(_, "parameters"))
        .orElse(prop("maven.compiler.parameters"))
    if (parameters.contains("true")) args += "-parameters"

    cfgOpt.flatMap(childText(_, "proc")).foreach(v => args += s"-proc:$v")

    cfgOpt
      .flatMap(cfg => Option(cfg.getChild("annotationProcessors")))
      .foreach { processors =>
        val processorNames = processors.getChildren
          .flatMap(p => Option(p.getValue).filter(_.nonEmpty))
        if (processorNames.nonEmpty) {
          args += "-processor"
          args += processorNames.mkString(",")
        }
      }

    cfgOpt.foreach { cfg =>
      def add(value: String): Unit =
        Option(value).filter(_.nonEmpty).foreach(args += _)

      Option(cfg.getChild("compilerArgs")).foreach { compilerArgs =>
        compilerArgs.getChildren
          .map(_.getValue)
          .filter(v => v != null && v.nonEmpty)
          .distinct
          .foreach(args += _)
      }
      childText(cfg, "compilerArg").foreach(add)
      childText(cfg, "compilerArgument").foreach(add)
      Option(cfg.getChild("compilerArguments")).foreach { compilerArgs =>
        compilerArgs.getChildren.foreach { child =>
          val flag = s"-${child.getName}"
          val value = Option(child.getValue).filter(_.nonEmpty)
          args += value.fold(flag)(v => s"$flag:$v")
        }
      }
    }

    args.result()
  }

  private def extractAnnotationProcessorPaths(
      cfgOpt: Option[Xpp3Dom],
      project: MavenProject,
  ): List[(String, String, String)] =
    cfgOpt
      .flatMap(cfg => Option(cfg.getChild("annotationProcessorPaths")))
      .toList
      .flatMap { paths =>
        paths.getChildren("path").toList.flatMap { path =>
          for {
            g <- childText(path, "groupId")
            a <- childText(path, "artifactId")
            v <- childText(path, "version").orElse(
              managedVersion(project, g, a, childText(path, "type"))
            )
          } yield (g, a, v)
        }
      }

  private def managedVersion(
      project: MavenProject,
      groupId: String,
      artifactId: String,
      tpe: Option[String],
  ): Option[String] =
    Option(project.getManagedVersionMap)
      .flatMap(m =>
        Option(m.get(s"$groupId:$artifactId:${tpe.getOrElse("jar")}"))
      )
      .map(_.getBaseVersion)

  private def extractScalaOptions(
      plugins: ju.Map[String, Plugin],
      isTest: Boolean,
  ): (List[String], Option[String]) = {
    val result = for {
      plugin <- Option(plugins.get(ScalaMavenPlugin))
      cfg <- compilerPluginConfiguration(plugin, isTest)
    } yield {
      val scalacArgs = Option(cfg.getChild("args"))
        .map(_.getChildren.map(_.getValue).toList)
        .getOrElse(Nil)
      val addScalacArgs = childText(cfg, "addScalacArgs")
        .map(_.split("\\|").map(_.trim).filter(_.nonEmpty).toList)
        .getOrElse(Nil)
      val scalaVersion = childText(cfg, "scalaVersion")
        .orElse(childText(cfg, "scalaCompatVersion"))
      (scalacArgs ++ addScalacArgs, scalaVersion)
    }
    result.getOrElse((Nil, None))
  }

  private def selectCompilerPluginConfiguration(
      plugin: Plugin,
      isTest: Boolean,
      log: Option[Log],
  ): Option[Xpp3Dom] = {
    val goal = if (isTest) "testCompile" else "compile"
    val defaultId = if (isTest) "default-testCompile" else "default-compile"
    val matching = plugin.getExecutions.asScala.toList
      .filter(compilerExecutionMatchesGoal(_, goal))

    val selected = matching
      .find(_.getId == defaultId)
      .orElse(matching.lastOption)

    selected match {
      case Some(execution) =>
        val skipped = matching.filter(_.getId != execution.getId).map(_.getId)
        if (skipped.nonEmpty)
          log.foreach(
            _.warn(
              s"metals-maven-plugin: using maven-compiler-plugin execution '${execution.getId}' " +
                s"for ${if (isTest) "test" else "main"} sources; skipping ${skipped.mkString(", ")}"
            )
          )
        mergedPluginConfiguration(plugin, execution)
      case None =>
        compilerPluginConfiguration(plugin, isTest)
    }
  }
}
