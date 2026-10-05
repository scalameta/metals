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
 * overwriting the old one; both stay selectable in Jupyter's own picker.
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

  // Bloop assigns a fresh session-scoped class directory per BSP
  // connection, so a plain restart alone can make an unchanged classpath
  // look stale. A kernel that was never installed still asks first,
  // since installing it may need to download Almond itself.
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
        // install() recovers failures into a successful Future (so it
        // can show its own error message instead of crashing the
        // caller), so a failed refresh would otherwise look up to date.
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

  // Not a hash, so two same-named notebooks in different directories
  // can't collide (AlmondKernelInstaller force-overwrites an existing
  // kernel dir). Switching the Almond version installs a new kernel
  // instead of overwriting the old one.
  def kernelIdFor(ipynbPath: AbsolutePath, almondVersion: String): String =
    s"metals-${sanitize(ipynbPath.toString)}-${sanitize(almondVersion)}"

  // Without escaping literal underscores first, "a/b" and "a_b" would
  // both sanitize to "a_b" and collide.
  private def sanitize(s: String): String =
    s.replace("_", "__").replaceAll("[^A-Za-z0-9_-]", "_")
}
