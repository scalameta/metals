package scala.meta.internal.metals

import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path

import scala.util.control.NonFatal

import scala.meta.internal.io.PlatformFileIO
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.SemanticdbClasspath
import scala.meta.internal.mtags.SemanticdbPath
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
    workspace: AbsolutePath,
    semanticdbFilesInJar: Boolean = true,
) {

  def onTargetRoots(): Unit = {
    for {
      targetRoot <- buildTargets.allTargetRoots
    } {
      if (semanticdbFilesInJar) {
        onChangeJar(targetRoot.toNIO)
      } else {
        onChangeDirectory(
          targetRoot.resolve(Directories.semanticdb).toNIO
        )
      }
    }
  }

  def reset(): Unit = {
    providers.foreach(_.reset())
  }

  def onDelete(semanticdbFile: SemanticdbPath): Unit = {
    SemanticdbClasspath.toScala(workspace, semanticdbFile).foreach {
      sourceFile =>
        providers.foreach(_.onDelete(sourceFile))
    }
  }

  /**
   * Handle EventType.OVERFLOW
   *
   * The overflow events comes either with a non-null or null path.
   *
   * In case the path is not null, we walk up the file tree to the parent `META-INF/semanticdb`
   * parent directory and re-index all of its `*.semanticdb` children.
   *
   * In case of a null path, we re-index `META-INF/semanticdb` for all targets
   */
  def onOverflow(path: SemanticdbPath): Unit = {
    if (path == null) {
      onTargetRoots()
    } else {
      path.semanticdbRoot.foreach(onChangeDirectory(_))
    }
  }

  private def onChangeJar(path: Path): Unit = {
    if (AbsolutePath(path).isFile) {
      val jarFS =
        PlatformFileIO.newJarFileSystem(AbsolutePath(path), create = false)
      jarFS.getRootDirectories.forEach { root =>
        val stream = Files.walk(root)
        try {
          stream.forEach { pathInJar =>
            {
              val pathInJarAbs = AbsolutePath(pathInJar)
              if (
                pathInJarAbs.isFile && pathInJar.getFileName.toString.endsWith(
                  ".semanticdb"
                )
              ) {
                val docs =
                  TextDocuments.parseFrom(
                    Files.readAllBytes(pathInJarAbs.toNIO)
                  )
                SemanticdbClasspath
                  .toScala(workspace, SemanticdbPath(pathInJarAbs))
                  .foreach { sourceFile =>
                    onChange(sourceFile, docs)
                  }
              }
            }
          }
        } finally {
          stream.close()
        }
      }
    }
  }

  private def onChangeDirectory(dir: Path): Unit = {
    if (Files.isDirectory(dir)) {
      val stream = Files.walk(dir)
      try {
        stream.forEach {
          case path if path.isSemanticdb =>
            onChange(SemanticdbPath(AbsolutePath(path)))
          case _ => ()
        }
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

  def onChange(file: SemanticdbPath): Unit = {
    if (!Files.isDirectory(file.toNIO)) {
      try {
        val docs = TextDocuments.parseFrom(Files.readAllBytes(file.toNIO))
        SemanticdbClasspath.toScala(workspace, file).foreach { sourceFile =>
          onChange(sourceFile, docs)
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
    }
  }
}
