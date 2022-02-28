package scala.meta.internal.metals.watcher

import java.io.IOException
import java.nio.file.Path

import scala.collection.mutable

import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.Cancelable
import scala.meta.internal.metals.Directories
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

import com.swoval.files.FileTreeDataViews.CacheObserver
import com.swoval.files.FileTreeDataViews.Converter
import com.swoval.files.FileTreeDataViews.Entry
import com.swoval.files.FileTreeRepositories
import com.swoval.files.FileTreeRepository

/**
 * Watch selected files and execute  a callback on file events.
 *
 * This class recursively watches selected directories and selected files.
 * File events can be further filtered by the `watchFiler` parameter, which can speed by watching for changes
 * by limiting the number of files that need to be hashed.
 *
 * We don't use the LSP dynamic file watcher capability because
 *
 * 1. the glob syntax is not defined in the LSP spec making it difficult to deliver a
 *    consistent file watching experience with all editor clients on all operating systems.
 * 2. we may have a lot of file watching events and it's presumably less overhead to
 *    get the notifications directly from the OS instead of through the editor via LSP.
 */
final class FileWatcher(
    workspaceDeferred: () => AbsolutePath,
    buildTargets: BuildTargets,
    watchFilter: Path => Boolean,
    onFileWatchEvent: FileWatcherEvent => Unit
) extends Cancelable {
  import FileWatcher._

  @volatile
  private var disposeAction: Option[() => Unit] = None

  override def cancel(): Unit = {
    disposeAction.map(_.apply())
    disposeAction = None
  }

  def restart(): Unit = {
    disposeAction.map(_.apply())

    val newDispose = startWatch(
      workspaceDeferred().toNIO,
      collectFilesToWatch(buildTargets),
      onFileWatchEvent,
      watchFilter
    )
    disposeAction = Some(newDispose)
  }
}

object FileWatcher {
  type Hash = Long

  private case class FilesToWatch(
      sourceFiles: Set[Path],
      sourceDirectories: Set[Path],
      semanticdDirectories: Set[Path]
  )

  private def collectFilesToWatch(buildTargets: BuildTargets): FilesToWatch = {
    val sourceDirectoriesToWatch = mutable.Set.empty[Path]
    val sourceFilesToWatch = mutable.Set.empty[Path]

    def collect(path: AbsolutePath): Unit = {
      if (buildTargets.isInsideSourceRoot(path)) {
        () // Do nothing, already covered by a source root
      } else if (path.isScalaOrJava) {
        sourceFilesToWatch.add(path.toNIO)
      } else {
        sourceDirectoriesToWatch.add(path.toNIO)
      }
    }
    // Watch the source directories for "goto definition" index.
    buildTargets.sourceRoots.foreach(collect)
    buildTargets.sourceItems.foreach(collect)
    val semanticdbs = buildTargets.allTargetRoots
      .filterNot(_.isJar)
      .map(_.resolve(Directories.semanticdb).toNIO)

    FilesToWatch(
      sourceFilesToWatch.toSet,
      sourceDirectoriesToWatch.toSet,
      semanticdbs.toSet
    )
  }

  /**
   * Start file watching
   *
   * Contains platform specific file watch initialization logic
   *
   * @param workspace current project workspace directory
   * @param filesToWatch source files and directories to watch
   * @param callback to execute on FileWatchEvent
   * @param watchFilter predicate that filters which files
   *        generate a FileWatchEvent on create/delete/change
   * @return a dispose action resources used by file watching
   */
  private def startWatch(
      workspace: Path,
      filesToWatch: FilesToWatch,
      callback: FileWatcherEvent => Unit,
      watchFilter: Path => Boolean
  ): () => Unit = {
    if (scala.util.Properties.isMac) {
      // Due to a hard limit on the number of FSEvents streams that can be opened on macOS,
      // only the root workspace directory is registered for a recursive watch.
      // However, the events are then filtered to receive only relevant events
      // and also to hash only revelevant files when watching for changes

      val trie = PathTrie(
        filesToWatch.sourceFiles ++ filesToWatch.sourceDirectories ++ filesToWatch.semanticdDirectories
      )
      val isWatched = trie.containsPrefixOf _

      val repo = initFileTreeRepository(
        path => watchFilter(path) && isWatched(path),
        callback
      )
      repo.register(workspace, Int.MaxValue)
      () => repo.close()
    } else {
      // Other OSes register all the files and directories individually
      val repo = initFileTreeRepository(watchFilter, callback)

      filesToWatch.sourceDirectories.foreach(repo.register(_, Int.MaxValue))
      filesToWatch.semanticdDirectories.foreach(repo.register(_, Int.MaxValue))
      filesToWatch.sourceFiles.foreach(repo.register(_, -1))

      () => repo.close()
    }
  }

  private def initFileTreeRepository(
      watchFilter: Path => Boolean,
      callback: FileWatcherEvent => Unit
  ): FileTreeRepository[Hash] = {
    val converter: Converter[Hash] = typedPath =>
      hashFile(
        typedPath.getPath(),
        watchFilter
      )
    val repo = FileTreeRepositories.get(converter, /*follow symlinks*/ true)

    repo.addCacheObserver(new CacheObserver[Hash] {
      override def onCreate(entry: Entry[Hash]): Unit = {
        val path = entry.getTypedPath().getPath()
        if (watchFilter(path)) callback(FileWatcherEvent.create(path))
      }
      override def onDelete(entry: Entry[Hash]): Unit = {
        val path = entry.getTypedPath().getPath()
        if (watchFilter(path)) callback(FileWatcherEvent.delete(path))
      }
      override def onUpdate(
          previous: Entry[Hash],
          current: Entry[Hash]
      ): Unit = {
        val path = current.getTypedPath().getPath()
        if (previous.getValue != current.getValue && watchFilter(path)) {
          callback(FileWatcherEvent.modify(path))
        }
      }
      override def onError(ex: IOException) = {}
    })
    repo
  }

  private def hashFile(path: Path, hashFilter: Path => Boolean): Hash = {
    if (hashFilter(path)) {
      path.toFile().lastModified()
    } else {
      0L
    }
  }
}
