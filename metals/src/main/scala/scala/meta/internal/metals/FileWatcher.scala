package scala.meta.internal.metals

import io.methvin.watcher.DirectoryChangeEvent
import io.methvin.watcher.DirectoryChangeListener
import io.methvin.watcher.DirectoryWatcher
import java.nio.file.Files
import java.nio.file.Path
import java.util
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

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

  private val executor = Executors.newFixedThreadPool(1)
  private var activeWatcher: Option[DirectoryWatcher] = None
  private var watching: CompletableFuture[Void] = new CompletableFuture()

  override def cancel(): Unit = {
    stopWatching()
    executor.shutdown()
    activeWatcher.foreach(_.close())
  }

  def restart(): Unit = {
    val directoriesToWatch = new util.ArrayList[Path]()
    def watch(dir: AbsolutePath): Unit = {
      if (!dir.isDirectory) {
        dir.createDirectories()
      }
      directoriesToWatch.add(dir.toNIO)
    }
    // Watch source directories for "goto definition" index.
    buildTargets.sourceDirectories.foreach(watch)
    buildTargets.scalacOptions.foreach { item =>
      // Watch META-INF/semanticdb directories for "find references" index.
      watch(item.targetroot.resolve(Directories.semanticdb))
    }
    startWatching(directoriesToWatch)
  }

  private def startWatching(paths: util.List[Path]): Unit = {
    stopWatching()
    val watcher = DirectoryWatcher
      .builder()
      .paths(paths)
      .listener(new Listener)
      .build()
    activeWatcher = Some(watcher)
    watching = watcher.watchAsync(executor)
  }

  private def stopWatching(): Unit = {
    activeWatcher.foreach(_.close())
    watching.cancel(true)
  }

  class Listener extends DirectoryChangeListener {
    override def onEvent(event: DirectoryChangeEvent): Unit = {
      if (Files.isRegularFile(event.path())) {
        didChangeWatchedFiles(event)
      }
    }
  }

}
