package scala.meta.internal.metals
// See NOTICE.md, this file contains parts that are derived from
// IntelliJ Scala, in particular JvmOpts.scala:
// https://github.com/JetBrains/intellij-scala/blob/e2c57778bb302a6f2f93e2628f6762ecbf76fb3a/scala/scala-impl/src/org/jetbrains/sbt/project/structure/JvmOpts.scala

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.meta.internal.io.FileIO
import scala.meta.io.AbsolutePath

/**
 * Support for the .jvmopts file loaded by the sbt launcher script as alternative to command line options.
 */
object JvmOpts {

  def fromWorkspace(workspace: AbsolutePath): List[String] = {
    val jvmOpts = workspace.resolve(".jvmopts")
    if (jvmOpts.isFile && Files.isReadable(jvmOpts.toNIO)) {
      val text = FileIO.slurp(jvmOpts, StandardCharsets.UTF_8)
      text.lines.map(_.trim).filter(_.startsWith("-")).toList
    } else {
      Nil
    }
  }

  def fromEnvironment: List[String] = {
    Option(System.getenv("JVM_OPTS")) match {
      case Some(value) => value.split(" ").toList
      case None => Nil
    }
  }

}
