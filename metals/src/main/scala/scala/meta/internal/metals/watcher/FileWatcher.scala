package scala.meta.internal.metals.watcher

import java.nio.file.Path
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

import scala.annotation.tailrec
import scala.collection.mutable

import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.Cancelable
import scala.meta.internal.metals.Directories
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.io.AbsolutePath

import com.swoval.files.FileTreeViews.Observer
import com.swoval.files.PathWatcher
import com.swoval.files.PathWatchers
import com.swoval.files.PathWatchers.Event.Kind

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
    config: MetalsServerConfig,
    workspaceDeferred: () => AbsolutePath,
    buildTargets: BuildTargets,
    watchFilter: Path => Boolean,
    onFileWatchEvent: FileWatcherEvent => Unit,
) extends Cancelable {
  import FileWatcher._

  @volatile
  private var stopWatcher: () => Unit = () => ()

  override def cancel(): Unit = {
    stopWatcher()
  }

  def start(
      files: Set[AbsolutePath]
  ): Unit = {
    stopWatcher = startWatch(
      config,
      workspaceDeferred().toNIO,
      PathsToWatch(files.map(_.toNIO), Set.empty),
      onFileWatchEvent,
      watchFilter,
    )
  }

  def start(): Unit = {
    stopWatcher = startWatch(
      config,
      workspaceDeferred().toNIO,
      collectPathsToWatch(buildTargets),
      onFileWatchEvent,
      watchFilter,
    )
  }
}

object FileWatcher {
  private case class PathsToWatch(
      files: Set[Path],
      directories: Set[Path],
  )

  private def collectPathsToWatch(buildTargets: BuildTargets): PathsToWatch = {
    val directories = mutable.Set.empty[Path]
    val files = mutable.Set.empty[Path]

    def collect(path: AbsolutePath): Unit = {
      val shouldBeWatched =
        !buildTargets.isInsideSourceRoot(path) &&
          !buildTargets.checkIfGeneratedSource(path.toNIO)

      if (shouldBeWatched) {
        if (buildTargets.isSourceFile(path))
          files.add(path.toNIO)
        else
          directories.add(path.toNIO)
      }
    }

    // Watch the source directories for "goto definition" index.
    buildTargets.sourceRoots.foreach(collect)
    buildTargets.sourceItems.foreach(collect)

    buildTargets.allTargetRoots
      .filterNot(_.isJar)
      .map(_.resolve(Directories.semanticdb).toNIO)
      .foreach(directories.add)

    PathsToWatch(
      files.toSet,
      directories.toSet,
    )
  }

  /**
   * Start file watching
   *
   * Contains platform specific file watch initialization logic
   *
   * @param config metals server configuration
   * @param workspace current project workspace directory
   * @param pathsToWatch source files and directories to watch
   * @param callback to execute on FileWatchEvent
   * @param watchFilter predicate that filters which files
   *        generate a FileWatchEvent on create/delete/change
   * @return a dispose action resources used by file watching
   */
  private def startWatch(
      config: MetalsServerConfig,
      workspace: Path,
      pathsToWatch: PathsToWatch,
      callback: FileWatcherEvent => Unit,
      watchFilter: Path => Boolean,
  ): () => Unit = {
    val watchEventQueue: BlockingQueue[FileWatcherEvent] =
      new LinkedBlockingQueue[FileWatcherEvent]

    val watcher: PathWatcher[PathWatchers.Event] = {
      if (scala.util.Properties.isMac) {
        // Due to a hard limit on the number of FSEvents streams that can be
        // opened on macOS, only up to 32 longest common prefixes of the files to
        // watch are registered for a recursive watch.
        // However, the events are then filtered to receive only relevant events

        val trie = PathTrie(
          pathsToWatch.files ++ pathsToWatch.directories
        )
        val isWatched = trie.containsPrefixOf _

        // Select up to `maxRoots` longest prefixes of all files in the trie for
        // watching. Watching the root of the workspace may have bad performance
        // implications if it contains many other projects that we don't need to
        // watch (eg. in a monorepo)
        val watchRoots =
          trie.longestPrefixes(workspace.getRoot(), config.macOsMaxWatchRoots)

        val watcher = initWatcher(
          path => watchFilter(path) && isWatched(path),
          watchEventQueue,
        )
        watchRoots.foreach { root =>
          scribe.debug(s"Registering root for file watching: $root")
          watcher.register(root, Int.MaxValue)
        }

        watcher
      } else {
        // Other OSes register all the files and directories individually
        val watcher = initWatcher(watchFilter, watchEventQueue)

        pathsToWatch.directories.foreach(
          watcher.register(_, Int.MaxValue)
        )
        pathsToWatch.files.foreach(watcher.register(_, -1))

        watcher
      }
    }

    val stopWatchingSignal: AtomicBoolean = new AtomicBoolean

    // queue is processed serially to defensively prevent possible races
    // when a file is created and then deleted in quick succesion.
    // Upstream callbacks implicitly assume that events for a single file
    // arrive and are processed serially.
    // This consumption could be parallelized, but in case in indexing,
    // compilation is usually the dominating bottleneck
    val thread = new Thread("metals-watch-callback-thread") {
      @tailrec def loop(): Unit = {
        try callback(watchEventQueue.take)
        catch { case _: InterruptedException => }
        if (!stopWatchingSignal.get) loop()
      }
      override def run(): Unit = loop()
      start()
    }

    () => {
      watcher.close()
      stopWatchingSignal.set(true)
      thread.interrupt()
    }
  }

  /**
   * Initialize file watcher
   *
   * File watch events are put into a queue which is processed separately
   * to prevent callbacks to be executed on the thread that watches
   * for file events.
   *
   * @param watchFilter for incoming file watch events
   * @param queue for file events
   * @return
   */
  private def initWatcher(
      watchFilter: Path => Boolean,
      queue: BlockingQueue[FileWatcherEvent],
  ): PathWatcher[PathWatchers.Event] = {
    val watcher = PathWatchers.get( /*follow symlinks*/ true)

    watcher.addObserver(new Observer[PathWatchers.Event] {
      override def onError(t: Throwable): Unit = {
        scribe.error(s"Error encountered during file watching", t)
      }

      override def onNext(event: PathWatchers.Event): Unit = {
        val path = event.getTypedPath.getPath
        if (path != null && watchFilter(path)) {
          event.getKind match {
            // Swoval PathWatcher may not disambiguate between create and modify events on macOS
            // due to how underlying OS APIs work. However such fidelity is not needed for metals.
            // If the distinction between create and modify is important some time in the future,
            // you may wish to use FileTreeRepository, which needs much more memory because
            // it caches the whole watched file tree.
            case Kind.Create =>
              queue.add(FileWatcherEvent.createOrModify(path))
            case Kind.Modify =>
              queue.add(FileWatcherEvent.createOrModify(path))
            case Kind.Delete =>
              queue.add(FileWatcherEvent.delete(path))
            case Kind.Overflow =>
              queue.add(FileWatcherEvent.overflow(path))
            case Kind.Error =>
              scribe.error("File watcher encountered an unknown error")
          }
        }
      }
    })

    watcher
  }
}
