package scala.meta.internal.metals.notebook

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise

import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.notebooks.AlmondKernelInstaller
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.MessageType

/**
 * Installs a real Jupyter kernel (via Almond) sharing the classpath of
 * whichever build target a notebook's cells currently resolve against (see
 * [[NotebookProvider.bestTarget]]), so "Run" actually executes cells
 * against the same dependencies its language features already see.
 *
 * The kernel id encodes the Almond version too, so switching
 * `notebookAlmondVersion` installs a separate kernel rather than
 * overwriting the old one — both stay selectable side by side in
 * Jupyter's own kernel picker.
 */
final class NotebookKernelInstaller(
    notebookProvider: NotebookProvider,
    buildTargets: BuildTargets,
    languageClient: MetalsLanguageClient,
    javaHome: => Option[String],
    configuredAlmondVersion: => Option[String],
)(implicit ec: ExecutionContext) {

  private def almondVersion: String =
    configuredAlmondVersion.getOrElse(
      AlmondKernelInstaller.defaultAlmondVersion
    )

  // A kernel that exists but no longer matches the classpath is refreshed
  // in place rather than reported as missing: Bloop assigns its BSP
  // client a fresh session-scoped class directory on every full restart,
  // which otherwise looks identical to a real classpath change and would
  // nag for permission to reinstall on every single restart. A kernel
  // that was never installed still requires that permission, since it
  // may need to download Almond itself for the first time.
  def isKernelUpToDate(ipynbPath: AbsolutePath): Future[Boolean] = {
    val resolved = for {
      target <- notebookProvider.bestTarget(ipynbPath)
      classpathFuture <- buildTargets.fullClasspath(target, Promise())
    } yield classpathFuture.flatMap { classpath =>
      val kernelId =
        NotebookKernelInstaller.kernelIdFor(ipynbPath, almondVersion)
      if (!AlmondKernelInstaller.exists(kernelId))
        Future.successful(false)
      else if (
        AlmondKernelInstaller.isUpToDate(kernelId, classpath.map(_.toNIO))
      )
        Future.successful(true)
      else
        // Re-check afterwards rather than assuming success: install()
        // recovers from failures into a successful Future (so it can
        // show its own error message instead of crashing the caller),
        // which would otherwise make a failed refresh look up to date.
        install(ipynbPath, notify = false).map(_ =>
          AlmondKernelInstaller.isUpToDate(kernelId, classpath.map(_.toNIO))
        )
    }
    resolved.getOrElse(Future.successful(false))
  }

  def installKernel(ipynbPath: AbsolutePath): Future[Unit] =
    install(ipynbPath, notify = true)

  private def install(
      ipynbPath: AbsolutePath,
      notify: Boolean,
  ): Future[Unit] =
    notebookProvider.bestTarget(ipynbPath) match {
      case None =>
        languageClient.showMessage(
          MessageType.Warning,
          "No build target found for this notebook.",
        )
        Future.successful(())
      case Some(target) =>
        val targetDisplayName =
          buildTargets
            .info(target)
            .map(_.getDisplayName)
            .getOrElse(target.getUri)
        val resolved = for {
          scalaTarget <- buildTargets.scalaTarget(target)
          classpathFuture <- buildTargets.fullClasspath(target, Promise())
        } yield classpathFuture.flatMap { classpath =>
          AlmondKernelInstaller.install(
            languageClient,
            javaHome,
            almondVersion,
            scalaTarget.scalaVersion,
            classpath.map(_.toNIO),
            kernelId =
              NotebookKernelInstaller.kernelIdFor(ipynbPath, almondVersion),
            displayName = s"Scala ($targetDisplayName, Almond $almondVersion)",
            notify = notify,
          )
        }
        resolved.getOrElse {
          languageClient.showMessage(
            MessageType.Error,
            s"Could not resolve a classpath for '$targetDisplayName'.",
          )
          Future.successful(())
        }
    }
}

object NotebookKernelInstaller {

  // Encodes the whole path (not a hash) so two same-named notebooks in
  // different directories can't collide and overwrite each other's kernel
  // (AlmondKernelInstaller force-overwrites an existing kernel dir).
  // Also encodes the Almond version, so switching it installs a new kernel
  // instead of overwriting the old one.
  def kernelIdFor(ipynbPath: AbsolutePath, almondVersion: String): String =
    s"metals-${sanitize(ipynbPath.toString)}-${sanitize(almondVersion)}"

  // Literal underscores are doubled up first, so a lone `_` in the result
  // always came from collapsing a disallowed character: without this,
  // "a/b" and "a_b" would otherwise both sanitize to "a_b" and collide.
  private def sanitize(s: String): String =
    s.replace("_", "__").replaceAll("[^A-Za-z0-9_-]", "_")
}
