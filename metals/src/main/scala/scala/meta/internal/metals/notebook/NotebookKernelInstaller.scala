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
 */
final class NotebookKernelInstaller(
    notebookProvider: NotebookProvider,
    buildTargets: BuildTargets,
    languageClient: MetalsLanguageClient,
    javaHome: => Option[String],
)(implicit ec: ExecutionContext) {

  def installKernel(ipynbPath: AbsolutePath): Future[Unit] =
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
            scalaTarget.scalaVersion,
            classpath.map(_.toNIO),
            kernelId = NotebookKernelInstaller.kernelIdFor(ipynbPath),
            displayName = s"Scala ($targetDisplayName)",
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

  // The filename stem alone collides for two same-named notebooks in
  // different directories; `AlmondKernelInstaller` force-overwrites an
  // existing kernel dir, so installing the second would silently replace
  // the first's unrelated kernel. Encoding the whole absolute path (rather
  // than hashing it) disambiguates them with no collision risk, and keeps
  // the id traceable back to its notebook by eye when browsing the
  // Jupyter kernels directory — the same tradeoff `notebookScratchDir`
  // makes by nesting on the real path instead of flattening it.
  def kernelIdFor(ipynbPath: AbsolutePath): String =
    s"metals-${ipynbPath.toString.replaceAll("[^A-Za-z0-9_-]", "_")}"
}
