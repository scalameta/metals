package scala.meta.internal.metals

import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

import scala.collection.mutable

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

import com.swoval.files.FileTreeDataViews.CacheObserver
import com.swoval.files.FileTreeDataViews.Converter
import com.swoval.files.FileTreeDataViews.Entry
import com.swoval.files.FileTreeRepositories
import com.swoval.files.FileTreeRepository
import com.swoval.files.TypedPath
import io.methvin.watcher.DirectoryChangeEvent
import io.methvin.watcher.DirectoryChangeEvent.EventType
import io.methvin.watcher.hashing.FileHasher
import io.methvin.watcher.hashing.HashCode

/**
 * Handles file watching of interesting files in this build.
 *
 * Tries to minimize file events by dynamically watching only relevant directories for
 * the structure of the build. We don't use the LSP dynamic file watcher capability because
 *
 * 1. the glob syntax is not defined in the LSP spec making it difficult to deliver a
 *    consistent file watching experience with all editor clients on all operating systems.
 * 2. we may have a lot of file watching events and it's presumably less overhead to
 *    get the notifications directly from the OS instead of through the editor via LSP.
 *
 * Given we rely on file watching for critical functionality like Goto Definition and it's
 * really difficult to reproduce/investigate file watching issues, I think it's best to
 * have a single file watching solution that we have control over.
 *
 * This class does not watch for changes in `*.sbt` files in the workspace directory and
 * in the `project/`. Those notifications are nice-to-have, but not critical. The library we are
 * using https://github.com/gmethvin/directory-watcher only supports recursive directory meaning
 * we would have to watch the workspace directory, resulting in a LOT of redundant file events.
 * Editors are free to send `workspace/didChangedWatchedFiles` notifications for these directories.
 */
final class FileWatcher(
    buildTargets: BuildTargets,
    didChangeWatchedFiles: DirectoryChangeEvent => Unit
) extends Cancelable {
  private def newRepository: FileTreeRepository[HashCode] = {
    val hasher = FileHasher.DEFAULT_FILE_HASHER
    val converter: Converter[HashCode] = (path: TypedPath) =>
      try hasher.hash(path.getPath)
      catch { case _: IOException => HashCode.empty() }
    val repo = FileTreeRepositories.get(converter, /*follow symlinks*/ true)
    def entryToEvent(kind: EventType, entry: Entry[_]): DirectoryChangeEvent =
      new DirectoryChangeEvent(kind, entry.getTypedPath.getPath, 1)
    repo.addCacheObserver(new CacheObserver[HashCode] {
      override def onCreate(entry: Entry[HashCode]): Unit = {
        didChangeWatchedFiles(entryToEvent(EventType.CREATE, entry))
      }
      override def onDelete(entry: Entry[HashCode]): Unit = {
        didChangeWatchedFiles(entryToEvent(EventType.DELETE, entry))
      }
      override def onUpdate(
          previous: Entry[HashCode],
          current: Entry[HashCode]
      ): Unit = {
        if (previous.getValue != current.getValue) {
          didChangeWatchedFiles(entryToEvent(EventType.MODIFY, current))
        }
      }
      override def onError(ex: IOException) = {}
    })
    repo
  }
  private val repository = new AtomicReference[FileTreeRepository[HashCode]]

  override def cancel(): Unit = {
    repository.getAndSet(null) match {
      case null =>
      case r => r.close()
    }
  }

  def restart(): Unit = {
    val sourceDirectoriesToWatch = mutable.Set.empty[Path]
    val sourceFilesToWatch = mutable.Set.empty[Path]
    val createdSourceDirectories = new java.util.ArrayList[AbsolutePath]()
    def watch(path: AbsolutePath, isSource: Boolean): Unit = {
      if (!path.isDirectory && !path.isFile) {
        val pathToCreate = if (path.isScalaOrJava) {
          AbsolutePath(path.toNIO.getParent())
        } else {
          path
        }
        val createdPaths = pathToCreate.createAndGetDirectories()
        // this is a workaround for MacOS, it will continue watching
        // directories even if they are removed, however it doesn't
        // work on some other systems like Linux
        if (isSource) {
          createdPaths.foreach(createdSourceDirectories.add)
        }
      }
      if (buildTargets.isInsideSourceRoot(path)) {
        () // Do nothing, already covered by a source root
      } else if (path.isScalaOrJava) {
        sourceFilesToWatch.add(path.toNIO)
      } else {
        sourceDirectoriesToWatch.add(path.toNIO)
      }
    }
    // Watch the source directories for "goto definition" index.
    buildTargets.sourceRoots.foreach(watch(_, isSource = true))
    buildTargets.sourceItems.foreach(watch(_, isSource = true))
    buildTargets.scalacOptions.foreach { item =>
      for {
        scalaInfo <- buildTargets.scalaInfo(item.getTarget)
      } {
        val targetroot = item.targetroot(scalaInfo.getScalaVersion)
        if (!targetroot.isJar) {
          // Watch META-INF/semanticdb directories for "find references" index.
          watch(
            targetroot.resolve(Directories.semanticdb),
            isSource = false
          )
        }
      }

    }
    val repo = repository.get match {
      case null =>
        val r = newRepository
        repository.set(r)
        r
      case r => r
    }
    // The second parameter of repo.register is the recursive depth of the watch.
    // A value of -1 means only watch this exact path. A value of 0 means only
    // watch the immediate children of the path. A value of 1 means watch the
    // children of the path and each child's children and so on.
    sourceDirectoriesToWatch.foreach(repo.register(_, Int.MaxValue))
    sourceFilesToWatch.foreach(repo.register(_, -1))

    // reverse sorting here is necessary to delete parent paths at the end
    createdSourceDirectories.asScala.sortBy(_.toNIO).reverse.foreach { dir =>
      if (dir.isEmptyDirectory) {
        dir.delete()
      }
    }
  }
}
