package scala.meta.internal.metals.debug

import java.io.File

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.ScalaMainClass
import ch.epfl.scala.{bsp4j => b}

/**
 * Wrapper around the bsp4j.ScalaMainClass to provide additional information which may be used by client.
 *
 * For backward compatibility reasons, it provides the same fields as the bsp4j.ScalaMainClass,
 * so it's safe for older clients to work without any change:
 * @param class the fully qualified name of the class
 * @param arguments the arguments to pass to the main method
 * @param jvmOptions the jvm options to pass to the jvm
 * @param environmentVariables the environment variables to pass to the process
 *
 * However, it also provides an additional field:
 * @param shellCommand which is the command to run in the shell to start the main class, it also
 * allows client to distinguish between old, bsp4j.ScalaMainClass and new ExtendedScalaMainClass
 * ---
 * To sum up:
 * - allow old clients to work without any change
 * - allow new clients to use shellCommand to run the main class directly in e.g. terminal
 */
case class ExtendedScalaMainClass private (
    `class`: String,
    arguments: java.util.List[String],
    jvmOptions: java.util.List[String],
    environmentVariables: java.util.List[String],
    shellCommand: String,
)

object ExtendedScalaMainClass {
  private def createCommand(
      javaHome: AbsolutePath,
      classpath: List[String],
      jvmOptions: List[String],
      arguments: List[String],
      mainClass: String,
  ): String = {
    val jvmOptsString = jvmOptions.mkString(" ")
    val classpathString = classpath.mkString(File.pathSeparator)
    val argumentsString = arguments.mkString(" ")
    s"$javaHome $jvmOptsString -classpath $classpathString $mainClass $argumentsString"
  }

  def apply(
      main: ScalaMainClass,
      env: b.JvmEnvironmentItem,
      javaHome: AbsolutePath,
  ): ExtendedScalaMainClass = {
    val jvmOpts = (main.getJvmOptions().asScala ++ env
      .getJvmOptions()
      .asScala).distinct.toList

    val jvmEnvVariables =
      env
        .getEnvironmentVariables()
        .asScala
        .map { case (key, value) =>
          s"$key=$value"
        }
        .toList

    val mainEnvVariables = Option(main.getEnvironmentVariables())
      .map(_.asScala.toList)
      .getOrElse(Nil)

    ExtendedScalaMainClass(
      main.getClassName(),
      main.getArguments(),
      jvmOpts.asJava,
      (jvmEnvVariables ++ mainEnvVariables).asJava,
      createCommand(
        javaHome,
        env.getClasspath().asScala.map(_.toAbsolutePath.toString).toList,
        jvmOpts,
        main.getArguments().asScala.toList,
        main.getClassName(),
      ),
    )
  }
}
