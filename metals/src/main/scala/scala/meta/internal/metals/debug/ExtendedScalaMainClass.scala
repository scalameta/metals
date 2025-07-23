package scala.meta.internal.metals.debug

import java.io.File
import java.nio.file.Paths

import scala.util.Properties
import scala.util.Try

import scala.meta.internal.metals.Directories
import scala.meta.internal.metals.ManifestJar
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.MD5
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
case class ExtendedScalaMainClass(
    `class`: String,
    arguments: java.util.List[String],
    jvmOptions: java.util.List[String],
    environmentVariables: java.util.List[String],
    shellCommand: String,
    classpath: String,
    javaBinary: String,
)

object ExtendedScalaMainClass {

  private def createCommand(
      javaBinary: AbsolutePath,
      classpathString: String,
      jvmOptions: List[String],
      arguments: List[String],
      mainClass: String,
  ): String = {

    def escape(s: String): String = {
      if (Properties.isWin) {
        s
      } else {
        s.replace("$", "\\$")
      }
    }

    def escapeMkString(list: List[String]): String = {
      if (list.nonEmpty) {
        list
          .map { str =>
            // Escape $ and other special characters
            val escaped = escape(str)
            s"\"$escaped\""
          }
          .mkString(" ")
      } else ""
    }

    val jvmOptsString = escapeMkString(jvmOptions)
    val argumentsString = escapeMkString(arguments)
    // We need to add "" to account for whitespace and also escaped \ before "
    val escapedJavaHome = javaBinary.toNIO.getRoot().toString +
      javaBinary.toNIO
        .iterator()
        .asScala
        .map(p => s""""${escape(p.toString)}"""")
        .mkString(File.separator)
    val safeJavaBinary =
      if (Properties.isWin) escapedJavaHome.replace("""\"""", """\\"""")
      else escapedJavaHome
    s"$safeJavaBinary $jvmOptsString -classpath \"$classpathString\" \"$mainClass\" $argumentsString"
  }

  /**
   * Create a manifest jar containing the classpath.
   * It's created in `.metals/tmp` with the name derived
   * from the classpath MD5 sum so that it's not created
   * every time.
   *
   * @param classpath classpath to put into the jar file
   * @param workspace current workspace path
   * @return path to the create jar as string
   */
  private def tempManifestJar(
      classpath: List[String],
      workspace: AbsolutePath,
  ): String = {
    val classpathDigest = MD5.compute(classpath.mkString)
    val manifestJar =
      workspace
        .resolve(Directories.tmp)
        .resolve(s"classpath_${classpathDigest}.jar")

    if (!manifestJar.exists) {
      ManifestJar.createManifestJar(manifestJar, classpath.map(Paths.get(_)))
    }

    manifestJar.toString()
  }

  def apply(
      main: ScalaMainClass,
      env: b.JvmEnvironmentItem,
      javaBinary: AbsolutePath,
      workspace: AbsolutePath,
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
    val classpath =
      env.getClasspath().asScala.map(_.toAbsolutePath.toString).toList
    val classpathString = Try(tempManifestJar(classpath, workspace)).getOrElse(
      classpath.mkString(File.pathSeparator)
    )
    val cmd = createCommand(
      javaBinary,
      classpathString,
      jvmOpts,
      main.getArguments().asScala.toList,
      main.getClassName(),
    )
    ExtendedScalaMainClass(
      main.getClassName(),
      main.getArguments(),
      jvmOpts.asJava,
      (jvmEnvVariables ++ mainEnvVariables).asJava,
      cmd,
      classpathString,
      javaBinary.toString(),
    )
  }
}
