package scala.meta.internal.metals

import io.methvin.watcher.DirectoryChangeEvent
import io.methvin.watcher.DirectoryChangeEvent.EventType
import io.methvin.watcher.DirectoryChangeListener
import io.methvin.watcher.DirectoryWatcher
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.Executors
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions
import org.eclipse.lsp4j.FileChangeType
import org.eclipse.lsp4j.FileEvent
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath
import scala.util.Properties

final class FileEvents(
    workspace: AbsolutePath,
    globs: DidChangeWatchedFilesRegistrationOptions,
    buildTargets: BuildTargets,
    didChangeWatchedFiles: DidChangeWatchedFilesParams => Unit
) extends Cancelable {
  private val matchers = globs.getWatchers.asScala.map { watcher =>
    FileSystems.getDefault.getPathMatcher(s"glob:${watcher.getGlobPattern}")
  }
  private val executor = Executors.newFixedThreadPool(1)
  private var activeWatcher: Option[DirectoryWatcher] = None
  override def cancel(): Unit = {
    executor.shutdown()
    activeWatcher.foreach(_.close())
  }
  class Listener extends DirectoryChangeListener {
    override def onEvent(event: DirectoryChangeEvent): Unit = {
      if (Files.isRegularFile(event.path()) &&
        matchers.exists(_.matches(event.path()))) {
        val uri = event.path().toUri.toString
        val changeType = event.eventType() match {
          case EventType.CREATE => FileChangeType.Created
          case EventType.MODIFY => FileChangeType.Changed
          case EventType.DELETE => FileChangeType.Deleted
          case _ => FileChangeType.Changed
        }
        val fileEvent = new FileEvent(uri, changeType)
        val params = new DidChangeWatchedFilesParams(
          Collections.singletonList(fileEvent)
        )
        didChangeWatchedFiles(params)
      }
    }
  }
  def start(): Unit = {
    activeWatcher.foreach(_.close())
    val paths = new java.util.ArrayList[Path]()
    paths.add(workspace.resolve("project").toNIO)
    buildTargets.sourceDirectories.foreach(dir => paths.add(dir.toNIO))
    paths.forEach { path =>
      if (!Files.exists(path)) {
        Files.createDirectories(path)
      }
    }
    val buildSbt = workspace.resolve("build.sbt").toNIO
    if (!Properties.isWin && Files.isRegularFile(buildSbt)) {
      paths.add(buildSbt)
    }
    val watcher = DirectoryWatcher
      .builder()
      .paths(paths)
      .listener(new Listener)
      .build()
    activeWatcher = Some(watcher)
    watcher.watchAsync(executor)
  }
}
