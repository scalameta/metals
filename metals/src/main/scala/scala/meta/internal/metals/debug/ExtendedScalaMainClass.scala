package scala.meta.internal.metals.debug

import java.io.File

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.ScalaMainClass
import ch.epfl.scala.{bsp4j => b}

case class ExtendedScalaMainClass private(
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
    val envVariables =
      (env.getEnvironmentVariables().asScala.map { case (key, value) =>
        s"$key=$value"
      } ++ main
        .getEnvironmentVariables()
        .asScala).toList.asJava

    ExtendedScalaMainClass(
      main.getClassName(),
      main.getArguments(),
      jvmOpts.asJava,
      envVariables,
      createCommand(
        javaHome,
        env.getClasspath().asScala.toList,
        jvmOpts,
        main.getArguments().asScala.toList,
        main.getClassName(),
      ),
    )
  }
}
