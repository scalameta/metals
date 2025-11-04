package bench

import java.nio.charset.StandardCharsets

import scala.meta.inputs.Input
import scala.meta.internal.io.FileIO
import scala.meta.io.AbsolutePath
import scala.meta.io.Classpath

case class Inflated(
    inputs: List[(Input.VirtualFile, AbsolutePath)]
) {
  lazy val linesOfCode: Long = inputs.foldLeft(0) { case (accum, input) =>
    accum + input._1.text.linesIterator.length
  }
  def take(n: Int): Inflated = {
    copy(inputs = inputs.take(n))
  }
  def filter(f: Input.VirtualFile => Boolean): Inflated = {
    val newInputs = inputs.filter { case (input, _) => f(input) }
    Inflated(newInputs)
  }
  def +(other: Inflated): Inflated =
    Inflated(other.inputs ++ inputs)

  def foreach(f: Input.VirtualFile => Unit): Unit =
    inputs.foreach { case (file, _) => f(file) }
}

object Inflated {

  def jars(classpath: Classpath): Inflated = {
    classpath.entries.foldLeft(Inflated(Nil)) { case (accum, next) =>
      accum + jar(next)
    }
  }
  def jar(file: AbsolutePath): Inflated = {
    FileIO.withJarFileSystem(
      file,
      create = false,
      close = true,
    ) { root =>
      val buf = List.newBuilder[(Input.VirtualFile, AbsolutePath)]
      FileIO.listAllFilesRecursively(root).foreach { file =>
        val path = file.toURI.toString()
        val text = FileIO.slurp(file, StandardCharsets.UTF_8)
        buf += ((Input.VirtualFile(path, text), file))
      }
      val inputs = buf.result()
      Inflated(inputs)
    }
  }
}
