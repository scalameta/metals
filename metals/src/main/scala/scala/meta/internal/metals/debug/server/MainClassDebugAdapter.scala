package scala.meta.internal.metals.debug.server

import scala.concurrent.ExecutionContext

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.ScalaMainClass
import ch.epfl.scala.debugadapter.CancelableFuture
import ch.epfl.scala.debugadapter.DebuggeeListener

class MainClassDebugAdapter(
    root: AbsolutePath,
    mainClass: ScalaMainClass,
    project: DebugeeProject,
    userJavaHome: Option[String],
)(implicit ec: ExecutionContext)
    extends MetalsDebuggee(project, userJavaHome) {
  def name: String =
    s"${getClass.getSimpleName}(${project.name}, ${mainClass.getClassName()})"

  def run(listener: DebuggeeListener): CancelableFuture[Unit] = {
    val mainClassEnvVariables =
      mainClass.getEnvironmentVariables().asScala.toList
    val buildServerEnvVariables = project.environmentVariablesAsStrings.toList
    // `mainClassEnvVariables` are the variables from IDE (launch.json for VSCode) which should take
    // preference over `buildServerEnvVariables`.
    val envVariables = buildServerEnvVariables ++ mainClassEnvVariables

    scribe.debug(s"""|Running main with debugger with environment variables: 
                     |\t${envVariables.mkString("\n\t")}
                     |and compile classpath:
                     |\t${classPath.mkString("\n\t")}
                     |and run classpath:
                     |\t${project.runClassPath.mkString("\n\t")}""".stripMargin)
    Run.runMain(
      root = root,
      classPath = project.runClassPath.map(_.toNIO),
      userJavaHome = userJavaHome,
      className = mainClass.getClassName,
      args = mainClass.getArguments().asScala.toList,
      jvmOptions = mainClass.getJvmOptions.asScala.toList,
      envVariables = envVariables,
      logger = new Logger(listener),
    )
  }
}
