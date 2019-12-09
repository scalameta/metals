package scala.meta.internal.pantsbuild

import java.nio.file.Path
import scala.collection.mutable
import scala.meta.internal.process.SystemProcess
import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.internal.ansi.LineListener
import scala.concurrent.ExecutionContext
import java.nio.file.Files
import scala.meta.internal.metals.MetalsEnrichments._

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
  def fromPants(workspace: Path, isCache: Boolean, targets: List[String])(
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
    val outputfile = workspace
      .resolve(".pants.d")
      .resolve("metals")
      .resolve(PantsConfiguration.outputFilename(targets) + "-filemap.txt")
    val shouldRun =
      !isCache || !Files.isRegularFile(outputfile)
    if (shouldRun) {
      SystemProcess.run(
        "pants filemap",
        workspace.resolve("pants").toString() ::
          "--print-exception-stacktrace=true" ::
          s"--filemap-output-file=$outputfile" ::
          "filemap" ::
          targets,
        workspace,
        EmptyCancelToken,
        lines
      )
    }
    Filemap.fromCache(workspace, outputfile)
  }

  private def fromCache(workspace: Path, cache: Path): Filemap = {
    val filemap = new Filemap
    if (Files.isRegularFile(cache)) {
      Files.lines(cache).asScala.foreach { line =>
        val split = line.split(" ", 2)
        if (split.length == 2) {
          val path = workspace.resolve(split(0))
          val target = split(1)
          filemap.addPath(target, path)
        }
      }
    }
    filemap
  }
}
