package scala.meta.internal.metals

import java.io.File
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

/**
 * Compiles all Java files for a build target using javac + annotation
 * processors and writes class files to a dedicated output directory.
 */
class JavaAnnotationProcessorBatchCompiler(
    classpath: Seq[Path],
    processorOpts: List[String],
    outputDir: Path,
) {

  def compile(javaFiles: Seq[Path]): Boolean =
    if (javaFiles.isEmpty) false
    else
      try {
        Files.createDirectories(outputDir)
        val compiler = javax.tools.ToolProvider.getSystemJavaCompiler()
        if (compiler == null) {
          scribe.warn(
            "[JavaAnnotationProcessorBatchCompiler] cannot find system Java compiler"
          )
          return false
        }
        val fm =
          compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)
        try {
          val compilationUnits =
            fm.getJavaFileObjectsFromPaths(javaFiles.asJava)
          val writer = new StringWriter()
          val task = compiler.getTask(
            writer,
            fm,
            null,
            buildOptions().asJava,
            null,
            compilationUnits,
          )
          val success = task.call()
          val output = writer.toString()
          if (output.nonEmpty)
            scribe.debug(s"[JavaAnnotationProcessorBatchCompiler] $output")
          success
        } finally {
          fm.close()
        }
      } catch {
        case NonFatal(e) =>
          scribe.warn(
            s"[JavaAnnotationProcessorBatchCompiler] compilation failed: ${e.getMessage}"
          )
          false
      }

  private def buildOptions(): List[String] = {
    val opts = List.newBuilder[String]
    opts += "-d"
    opts += outputDir.toString()
    if (classpath.nonEmpty) {
      opts += "-classpath"
      opts += classpath.mkString(File.pathSeparator)
    }
    opts ++= processorOpts
    opts.result()
  }
}
