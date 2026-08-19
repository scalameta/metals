package scala.meta.internal.metals.mbt

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

import scala.meta.internal.builds.Digest
import scala.meta.internal.metals.Configs
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.mtags.MD5
import scala.meta.io.AbsolutePath
import scala.meta.io.RelativePath

import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions
import org.eclipse.lsp4j.FileSystemWatcher
import org.eclipse.lsp4j.Registration
import org.eclipse.lsp4j.RegistrationParams
import org.eclipse.lsp4j.Unregistration
import org.eclipse.lsp4j.UnregistrationParams
import org.eclipse.lsp4j.jsonrpc.messages.{Either => JEither}

/**
 * The extra files an MBT build asks Metals to watch via `watchedFiles` in
 * `.metals/mbt.json`.
 *
 * Every import that writes `.metals/mbt.json` also calls [[update]] with the
 * merged build, which does two things:
 *  - registers the declared paths with the client, so edits come back as
 *    `workspace/didChangeWatchedFiles` notifications, and
 *  - folds their contents into [[digest]], which is part of the MBT import
 *    digest.
 *
 * Changing a watched file therefore invalidates the digest and goes through the
 * regular re-import prompt, exactly like changing `pom.xml` does for Maven.
 */
final class MbtWatchedFiles(
    workspace: AbsolutePath,
    languageClient: MetalsLanguageClient,
    globSyntax: () => Configs.GlobSyntaxConfig,
    supportsDynamicRegistration: () => Boolean,
) {

  private val watched =
    new AtomicReference[Option[List[RelativePath]]](None)
  private val isRegistered = new AtomicBoolean(false)

  /**
   * Files currently watched, falling back to the build already on disk when no
   * import has run yet in this session.
   */
  def paths: List[RelativePath] =
    watched.get().getOrElse {
      val fromDisk = resolve(MbtBuild.fromWorkspace(workspace))
      watched.compareAndSet(None, Some(fromDisk))
      fromDisk
    }

  def isWatched(path: AbsolutePath): Boolean = {
    val current = paths
    current.nonEmpty &&
    path.toRelativeInside(workspace).exists(current.contains)
  }

  /**
   * Digest of the watched files' contents, `None` when nothing is watched.
   */
  def digest: Option[String] = {
    val current = paths
    Option.when(current.nonEmpty) {
      val digest = MessageDigest.getInstance("MD5")
      for (relative <- current.sortBy(_.toString)) {
        digest.update(relative.toString.getBytes(StandardCharsets.UTF_8))
        Digest.digestFileBytes(workspace.resolve(relative), digest)
      }
      MD5.bytesToHex(digest.digest())
    }
  }

  /**
   * Replaces the watched files with the ones declared by `build` and
   * re-registers the client watchers.
   */
  def update(build: MbtBuild): Unit = {
    val resolved = resolve(build)
    watched.set(Some(resolved))
    register(resolved)
  }

  /**
   * Registers watchers for the build already on disk. Used on startup, when the
   * digest is unchanged and no import runs.
   */
  def initialize(): Unit = update(MbtBuild.fromWorkspace(workspace))

  private def resolve(build: MbtBuild): List[RelativePath] =
    for {
      declared <- build.explicitWatchedFiles
      relative <- relativize(declared)
    } yield relative

  private def relativize(declared: String): Option[RelativePath] = {
    val resolved = workspace.toNIO.resolve(declared).normalize()
    if (resolved.startsWith(workspace.toNIO) && resolved != workspace.toNIO)
      Some(RelativePath(workspace.toNIO.relativize(resolved)))
    else {
      scribe.warn(
        s"mbt-watched-files: ignoring '$declared', watched files must be inside the workspace."
      )
      None
    }
  }

  private def register(relativePaths: List[RelativePath]): Unit =
    if (!supportsDynamicRegistration()) {
      if (relativePaths.nonEmpty)
        scribe.warn(
          "mbt-watched-files: the client does not support dynamic registration of " +
            "'workspace/didChangeWatchedFiles', changes to watched files will not trigger a re-import."
        )
    } else {
      unregister()
      if (relativePaths.nonEmpty) {
        val root =
          if (globSyntax().isUri) workspace.toURI.toString.stripSuffix("/")
          else workspace.toString()
        val watchers =
          for (relative <- relativePaths)
            yield new FileSystemWatcher(
              JEither.forLeft(
                s"$root/${MbtGlobMatcher.normalizeSlashes(relative.toString)}"
              )
            )
        isRegistered.set(true)
        languageClient
          .registerCapability(
            new RegistrationParams(
              List(
                new Registration(
                  MbtWatchedFiles.registrationId,
                  MbtWatchedFiles.method,
                  new DidChangeWatchedFilesRegistrationOptions(watchers.asJava),
                )
              ).asJava
            )
          )
          .whenComplete { (_, error) =>
            if (error != null)
              scribe.warn(
                "mbt-watched-files: client rejected the file watcher registration",
                error,
              )
          }
        scribe.info(
          s"mbt-watched-files: watching ${relativePaths.mkString(", ")}"
        )
      }
    }

  private def unregister(): Unit =
    if (isRegistered.getAndSet(false)) {
      languageClient.unregisterCapability(
        new UnregistrationParams(
          List(
            new Unregistration(
              MbtWatchedFiles.registrationId,
              MbtWatchedFiles.method,
            )
          ).asJava
        )
      )
    }
}

object MbtWatchedFiles {
  private val registrationId = "mbt-watched-files"
  private val method = "workspace/didChangeWatchedFiles"
}
