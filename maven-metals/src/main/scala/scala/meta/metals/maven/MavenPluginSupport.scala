package scala.meta.metals.maven

import java.io.File
import java.{util => ju}

import scala.jdk.CollectionConverters._
import scala.util.matching.Regex

import org.apache.maven.model.Plugin
import org.apache.maven.model.PluginExecution
import org.apache.maven.project.MavenProject
import org.codehaus.plexus.util.xml.Xpp3Dom

private[maven] object MavenPluginSupport {

  val ScalaMavenPlugin = "net.alchim31.maven:scala-maven-plugin"
  val JavaCompilerPlugin = "org.apache.maven.plugins:maven-compiler-plugin"
  val MavenEnforcerPlugin = "org.apache.maven.plugins:maven-enforcer-plugin"
  val Antlr4MavenPlugin = "org.antlr:antlr4-maven-plugin"
  val BuildHelperMavenPlugin = "org.codehaus.mojo:build-helper-maven-plugin"
  val MavenToolchainsPlugin = "org.apache.maven.plugins:maven-toolchains-plugin"
  val ModelloMavenPlugin = "org.codehaus.modello:modello-maven-plugin"
  val ProtobufMavenPlugin = "org.xolstice.maven.plugins:protobuf-maven-plugin"

  private val PropertyRef: Regex = """\$\{([^}]+)\}""".r

  def childText(dom: Xpp3Dom, name: String): Option[String] =
    Option(dom.getChild(name)).map(_.getValue).filter(_.nonEmpty)

  def effectivePlugins(project: MavenProject): ju.Map[String, Plugin] = {
    val result = new ju.LinkedHashMap[String, Plugin]()
    Option(project.getBuild).foreach { build =>
      Option(build.getPluginManagement).foreach { pm =>
        Option(pm.getPluginsAsMap).foreach(result.putAll)
      }
      Option(build.getPluginsAsMap).foreach(result.putAll)
    }
    result
  }

  def mergedPluginConfiguration(plugin: Plugin): Option[Xpp3Dom] = {
    val pluginCfg =
      Option(plugin.getConfiguration).map(_.asInstanceOf[Xpp3Dom])
    val execCfgs = plugin.getExecutions.asScala
      .flatMap(e => Option(e.getConfiguration).map(_.asInstanceOf[Xpp3Dom]))
    mergeConfigurations(execCfgs.toSeq ++ pluginCfg)
  }

  def mergedPluginConfiguration(
      plugin: Plugin,
      e: PluginExecution,
  ): Option[Xpp3Dom] = {
    val pluginCfg =
      Option(plugin.getConfiguration).map(_.asInstanceOf[Xpp3Dom])
    val execCfg =
      Option(e.getConfiguration).map(_.asInstanceOf[Xpp3Dom])
    mergeConfigurations(execCfg.toSeq ++ pluginCfg)
  }

  def compilerPluginConfiguration(
      plugin: Plugin,
      isTest: Boolean,
  ): Option[Xpp3Dom] = {
    val pluginCfg =
      Option(plugin.getConfiguration).map(_.asInstanceOf[Xpp3Dom])
    val goalFilter = if (isTest) "testCompile" else "compile"
    val allExecCfgs = plugin.getExecutions.asScala
      .flatMap(e =>
        Option(e.getConfiguration).map(_.asInstanceOf[Xpp3Dom] -> e)
      )
      .toSeq
    val goalSpecificCfgs = allExecCfgs
      .filter { case (_, exec) =>
        compilerExecutionMatchesGoal(exec, goalFilter)
      }
      .map(_._1)
    val execCfgs =
      if (goalSpecificCfgs.nonEmpty) goalSpecificCfgs
      else allExecCfgs.map(_._1)
    mergeConfigurations(execCfgs ++ pluginCfg)
  }

  def compilerExecutionMatchesGoal(
      execution: PluginExecution,
      goal: String,
  ): Boolean =
    execution.getGoals.asScala.contains(goal) ||
      execution.getId == s"default-$goal"

  private def mergeConfigurations(
      configurations: Seq[Xpp3Dom]
  ): Option[Xpp3Dom] =
    configurations.reduceOption(Xpp3Dom.mergeXpp3Dom)

  def absolutePath(path: String, project: MavenProject): String = {
    val file = new File(path)
    if (file.isAbsolute) file.getAbsolutePath
    else new File(project.getBasedir, path).getAbsolutePath
  }

  def interpolatePath(value: String, project: MavenProject): String =
    PropertyRef.replaceAllIn(
      value,
      m =>
        Regex.quoteReplacement(
          propertyValue(m.group(1), project).getOrElse(m.matched)
        ),
    )

  private def propertyValue(
      name: String,
      project: MavenProject,
  ): Option[String] =
    name match {
      case "basedir" | "project.basedir" =>
        Some(project.getBasedir.getAbsolutePath)
      case "build.directory" | "project.build.directory" =>
        Some(project.getBuild.getDirectory)
      case _ =>
        Option(project.getProperties.getProperty(name))
    }
}
