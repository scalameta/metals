package scala.meta.internal.pantsbuild

import java.nio.file.Path
import scala.collection.mutable
import scala.meta.internal.process.SystemProcess
import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.internal.ansi.LineListener
import scala.concurrent.ExecutionContext

class Filemap(
    map: mutable.Map[String, mutable.ArrayBuffer[Path]] = mutable.Map.empty
) {
  override def toString(): String =
    pprint.tokenize(map).mkString("Filemap(", "", ")")
  def fileCount(): Int =
    map.valuesIterator.foldLeft(0)(_ + _.length)
  def iterator(): Iterator[(String, collection.Seq[Path])] =
    map.iterator
  private[Filemap] def addPath(target: String, path: Path): Unit = {
    val value =
      map.getOrElseUpdate(target, mutable.ArrayBuffer.empty[Path])
    value += path
  }
  def forTarget(key: String): List[Path] = map.getOrElse(key, Nil).toList
}

object Filemap {
  def fromPants(workspace: Path, targets: List[String])(
      implicit ec: ExecutionContext
  ): Filemap = {
    val filemap = new Filemap()
    val lines = new LineListener(line => {
      val split = line.split(" ", 2)
      if (split.length == 2) {
        val path = workspace.resolve(split(0))
        val target = split(1)
        filemap.addPath(target, path)
      }
    })
    SystemProcess.run(
      "pants filemap",
      workspace.resolve("pants").toString() ::
        "--print-exception-stacktrace=true" ::
        "filemap" ::
        targets,
      workspace,
      EmptyCancelToken,
      lines
    )
    filemap
  }
}
