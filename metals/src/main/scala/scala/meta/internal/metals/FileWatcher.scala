package scala.meta.internal.metals

import java.nio.file.Files
import java.nio.file.Path
import java.util
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.{util => ju}

import scala.collection.mutable

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

import io.methvin.watcher.DirectoryChangeEvent
import io.methvin.watcher.DirectoryChangeEvent.EventType
import io.methvin.watcher.DirectoryChangeListener
import io.methvin.watcher.DirectoryWatcher

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

  private val directoryExecutor = Executors.newFixedThreadPool(1)
  private val fileExecutor = Executors.newFixedThreadPool(1)

  ThreadPools.discardRejectedRunnables(
    "FileWatcher.executor",
    directoryExecutor
  )
  ThreadPools.discardRejectedRunnables(
    "FileWatcher.fileExecutor",
    fileExecutor
  )

  private var activeWatchers: Option[Watchers] = None

  override def cancel(): Unit = {
    stopWatching()
    directoryExecutor.shutdown()
    activeWatchers.foreach(_.close())
  }

  def restart(): Unit = {
    val sourceDirectoriesToWatch = mutable.Set.empty[Path]
    val sourceFilesToWatch = mutable.Set.empty[Path]
    val createdSourceDirectories = new util.ArrayList[AbsolutePath]()
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
    startWatching(
      directories = sourceDirectoriesToWatch.toSet,
      files = sourceFilesToWatch.toSet
    )
    // reverse sorting here is necessary to delete parent paths at the end
    createdSourceDirectories.asScala.sortBy(_.toNIO).reverse.foreach { dir =>
      if (dir.isEmptyDirectory) {
        dir.delete()
      }
    }
  }

  private def startWatching(
      directories: Set[Path],
      files: Set[Path]
  ): Unit = {
    stopWatching()
    val directoryWatcher = DirectoryWatcher
      .builder()
      .paths(directories.toList.asJava)
      .listener(new DirectoryListener())
      // File hashing is necessary for correctness, see:
      // https://github.com/scalameta/metals/pull/1153
      .fileHashing(true)
      .build()

    val fileDirectories = files.map(_.getParent())
    val fileWatcher = DirectoryWatcher
      .builder()
      .paths(fileDirectories.toList.asJava)
      .listener(new FileListener(files))
      // File hashing is necessary for correctness, see:
      // https://github.com/scalameta/metals/pull/1153
      .fileHashing(true)
      .build()

    val watchers = Watchers(
      directoryWatcher,
      directoryWatcher.watchAsync(directoryExecutor),
      fileWatcher,
      fileWatcher.watchAsync(fileExecutor)
    )

    activeWatchers = Some(watchers)
  }

  private def stopWatching(): Unit = {
    try activeWatchers.foreach(_.close())
    catch {
      // safe to ignore because we're closing the file watcher and
      // this error won't affect correctness of Metals.
      case _: ju.ConcurrentModificationException =>
    }
    activeWatchers.foreach(_.cancel(false))
  }

  case class Watchers(
      directory: DirectoryWatcher,
      directoryWatching: CompletableFuture[Void],
      file: DirectoryWatcher,
      fileWatching: CompletableFuture[Void]
  ) {

    def cancel(mayInterruptIfRunning: Boolean): Boolean = {
      directoryWatching.cancel(mayInterruptIfRunning)
      fileWatching.cancel(mayInterruptIfRunning)
    }

    def close(): Unit = {
      directory.close()
      file.close()
    }
  }

  class DirectoryListener extends DirectoryChangeListener {
    override def onEvent(event: DirectoryChangeEvent): Unit = {
      // in non-MacOS systems the path will be null
      if (event.eventType() == EventType.OVERFLOW) {
        didChangeWatchedFiles(event)
      } else if (!Files.isDirectory(event.path())) {
        didChangeWatchedFiles(event)
      }
    }
  }

  class FileListener(files: Set[Path]) extends DirectoryChangeListener {
    override def onEvent(event: DirectoryChangeEvent): Unit = {
      // in non-MacOS systems the path will be null
      if (event.eventType() == EventType.OVERFLOW) {
        didChangeWatchedFiles(event)
      } else if (files(event.path())) {
        didChangeWatchedFiles(event)
      }
    }
  }

}
