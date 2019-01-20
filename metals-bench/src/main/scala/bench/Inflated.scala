package bench

import java.nio.charset.StandardCharsets
import scala.meta.inputs.Input
import scala.meta.internal.io.FileIO
import scala.meta.io.AbsolutePath
import scala.meta.io.Classpath

case class Inflated(inputs: List[Input.VirtualFile], linesOfCode: Long) {
  def filter(f: Input.VirtualFile => Boolean): Inflated = {
    val newInputs = inputs.filter(input => f(input))
    val newLinesOfCode = newInputs.foldLeft(0) {
      case (accum, input) =>
        accum + input.text.linesIterator.length
    }
    Inflated(newInputs, newLinesOfCode)
  }
  def +(other: Inflated): Inflated =
    Inflated(other.inputs ++ inputs, other.linesOfCode + linesOfCode)
}

object Inflated {

  def jars(classpath: Classpath): Inflated = {
    classpath.entries.foldLeft(Inflated(Nil, 0L)) {
      case (accum, next) =>
        accum + jar(next)
    }
  }
  def jar(file: AbsolutePath): Inflated = {
    FileIO.withJarFileSystem(
      file,
      create = false,
      close = true
    ) { root =>
      var lines = 0L
      val buf = List.newBuilder[Input.VirtualFile]
      FileIO.listAllFilesRecursively(root).foreach { file =>
        val path = file.toString()
        val text = FileIO.slurp(file, StandardCharsets.UTF_8)
        lines += text.linesIterator.length
        buf += Input.VirtualFile(path, text)
      }
      val inputs = buf.result()
      Inflated(inputs, lines)
    }
  }
}
