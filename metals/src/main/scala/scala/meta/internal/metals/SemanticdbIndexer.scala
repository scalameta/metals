package scala.meta.internal.metals

import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path

import scala.util.control.NonFatal

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.SemanticdbClasspath
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.semanticdb.TextDocuments
import scala.meta.io.AbsolutePath

import com.google.protobuf.InvalidProtocolBufferException

trait SemanticdbFeatureProvider {
  def onChange(docs: TextDocuments, path: AbsolutePath): Unit
  def onDelete(path: AbsolutePath): Unit
  def reset(): Unit
}

class SemanticdbIndexer(
    providers: List[SemanticdbFeatureProvider],
    buildTargets: BuildTargets,
    workspace: AbsolutePath
) {

  def onTargetRoots(): Unit = {
    for {
      targetRoot <- buildTargets.allTargetRoots
    } {
      onChangeDirectory(targetRoot.resolve(Directories.semanticdb).toNIO)
    }
  }

  def reset(): Unit = {
    providers.foreach(_.reset())
  }

  def onDelete(file: Path): Unit = {
    val absolutePath = AbsolutePath(file)
    providers.foreach(_.onDelete(absolutePath))
  }

  private def onChangeDirectory(dir: Path): Unit = {
    if (Files.isDirectory(dir)) {
      val stream = Files.walk(dir)
      try {
        stream.forEach(onChange(_))
      } finally {
        stream.close()
      }
    }
  }

  def onChange(path: AbsolutePath, textDocument: TextDocument): Unit = {
    val docs = TextDocuments(Seq(textDocument))
    onChange(path, docs)
  }

  private def onChange(path: AbsolutePath, docs: TextDocuments): Unit =
    providers.foreach(_.onChange(docs, path))

  def onChange(file: Path): Unit = {
    if (!Files.isDirectory(file)) {
      if (file.isSemanticdb) {
        try {
          val doc = TextDocuments.parseFrom(Files.readAllBytes(file))
          SemanticdbClasspath.toScala(workspace, AbsolutePath(file)).foreach {
            sourceFile => onChange(sourceFile, doc)
          }
        } catch {
          /* @note in some cases file might be created or modified, but not actually finished
           * being written. In that case, exception here is expected and a new event will
           * follow after it was finished.
           */
          case e: InvalidProtocolBufferException =>
            scribe.debug(s"$file is not yet ready", e)
          /* @note FileSystemException is thrown on Windows instead of InvalidProtocolBufferException */
          case e: FileSystemException =>
            scribe.debug(s"$file is not yet ready", e)
          case NonFatal(e) =>
            scribe.warn(s"unexpected error processing the file $file", e)
        }
      } else {
        scribe.warn(s"not a semanticdb file: $file")
      }
    }
  }
}
