package bench

import java.nio.charset.StandardCharsets

import scala.meta.inputs.Input
import scala.meta.internal.io.FileIO
import scala.meta.io.AbsolutePath
import scala.meta.io.Classpath

case class Inflated(inputs: List[(Input.VirtualFile, AbsolutePath)], linesOfCode: Long) {
  def filter(f: Input.VirtualFile => Boolean): Inflated = {
    val newInputs = inputs.filter{case (input, _) => f(input)}
    val newLinesOfCode = newInputs.foldLeft(0) { case (accum, input) =>
      accum + input._1.text.linesIterator.length
    }
    Inflated(newInputs, newLinesOfCode)
  }
  def +(other: Inflated): Inflated =
    Inflated(other.inputs ++ inputs, other.linesOfCode + linesOfCode)

  def foreach(f: Input.VirtualFile => Unit): Unit =
    inputs.foreach{ case (file, _) => f(file)}
}

object Inflated {

  def jars(classpath: Classpath): Inflated = {
    classpath.entries.foldLeft(Inflated(Nil, 0L)) { case (accum, next) =>
      accum + jar(next)
    }
  }
  def jar(file: AbsolutePath): Inflated = {
    FileIO.withJarFileSystem(
      file,
      create = false,
      close = true,
    ) { root =>
      var lines = 0L
      val buf = List.newBuilder[(Input.VirtualFile, AbsolutePath)]
      FileIO.listAllFilesRecursively(root).foreach { file =>
        val path = file.toURI.toString()
        val text = FileIO.slurp(file, StandardCharsets.UTF_8)
        lines += text.linesIterator.length
        buf += ((Input.VirtualFile(path, text), file))
      }
      val inputs = buf.result()
      Inflated(inputs, lines)
    }
  }
}
