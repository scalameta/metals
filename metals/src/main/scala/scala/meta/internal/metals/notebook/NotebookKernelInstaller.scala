package scala.meta.internal.metals.notebook

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.control.NonFatal

import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.notebooks.AlmondKernelInstaller
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.ShowMessageRequestParams

/**
 * Installs a Jupyter kernel (via Almond) with the classpath of the build
 * target a notebook's cells resolve against (see
 * [[NotebookProvider.bestTarget]]).
 *
 * The kernel id includes the Almond version, so switching
 * `notebookAlmondVersion` installs a second kernel next to the old one.
 */
final class NotebookKernelInstaller(
    notebookProvider: NotebookProvider,
    buildTargets: BuildTargets,
    languageClient: MetalsLanguageClient,
    javaHome: () => Option[String],
    configuredAlmondVersion: () => Option[String],
)(implicit ec: ExecutionContext) {
  import NotebookKernelInstaller._

  private def almondVersion: String =
    configuredAlmondVersion().getOrElse(
      AlmondKernelInstaller.defaultAlmondVersion
    )

  /**
   * Installs or refreshes the notebook's kernel and returns it.
   *
   * Bloop gives each BSP connection its own `classes-Metals-<uuid>`
   * directory, so a Metals restart alone makes an installed kernel stale.
   * Stale kernels get reinstalled silently. A missing kernel may need an
   * Almond download, so with `askBeforeInstall` the user confirms first.
   *
   * @return None if no kernel is installed after this call
   */
  def ensureKernel(
      ipynbPath: AbsolutePath,
      askBeforeInstall: Boolean,
  ): Future[Option[NotebookKernel]] =
    notebookProvider.bestTarget(ipynbPath) match {
      case None =>
        if (!askBeforeInstall)
          languageClient.showMessage(
            MessageType.Warning,
            "No build target found for this notebook.",
          )
        Future.successful(None)
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
          val kernel = NotebookKernel(
            kernelIdFor(ipynbPath, almondVersion),
            s"Scala ($targetDisplayName, Almond $almondVersion)",
          )
          val paths = classpath.map(_.toNIO)
          def upToDate =
            AlmondKernelInstaller.isUpToDate(kernel.kernelId, paths)
          def install(fresh: Boolean): Future[Option[NotebookKernel]] =
            AlmondKernelInstaller
              .install(
                languageClient,
                javaHome(),
                almondVersion,
                scalaTarget.scalaVersion,
                paths,
                kernel.kernelId,
                kernel.displayName,
                notify = fresh,
              )
              // install() recovers failures into a successful Future so it
              // can show its own error message.
              .map(_ =>
                Option.when(upToDate)(kernel.copy(newlyInstalled = fresh))
              )
          if (upToDate) Future.successful(Some(kernel))
          else if (AlmondKernelInstaller.exists(kernel.kernelId))
            install(fresh = false)
          else if (askBeforeInstall)
            confirmInstall().flatMap { confirmed =>
              if (confirmed) install(fresh = true)
              else Future.successful(None)
            }
          else install(fresh = true)
        }
        resolved
          .getOrElse {
            if (!askBeforeInstall)
              languageClient.showMessage(
                MessageType.Error,
                s"Could not resolve a classpath for '$targetDisplayName'.",
              )
            Future.successful(None)
          }
          .recover { case NonFatal(e) =>
            scribe.error(s"failed to ensure Almond kernel for '$ipynbPath'", e)
            None
          }
    }

  private def confirmInstall(): Future[Boolean] = {
    val params = new ShowMessageRequestParams()
    params.setType(MessageType.Info)
    params.setMessage(
      "Install a Jupyter kernel (via Almond) to run this notebook's Scala cells?"
    )
    params.setActions(List(installAction, notNowAction).asJava)
    languageClient
      .showMessageRequest(params)
      .asScala
      .map(_ == installAction)
  }
}

object NotebookKernelInstaller {

  /**
   * @param kernelId the kernelspec's directory name, as Jupyter lists it
   * @param newlyInstalled true if the kernel didn't exist before this call.
   *                       Jupyter only scans kernelspecs on startup, so
   *                       the client has to make it rescan.
   */
  final case class NotebookKernel(
      kernelId: String,
      displayName: String,
      newlyInstalled: Boolean = false,
  )

  private val installAction = new MessageActionItem("Install kernel")
  private val notNowAction = new MessageActionItem("Not now")

  // Not a hash, so two same-named notebooks in different directories
  // can't collide (AlmondKernelInstaller force-overwrites an existing
  // kernel dir). Switching the Almond version installs a new kernel
  // instead of overwriting the old one.
  def kernelIdFor(ipynbPath: AbsolutePath, almondVersion: String): String = {
    val versionPart = sanitize(almondVersion)
    val full = s"metals-${sanitize(ipynbPath.toString)}-$versionPart"
    if (full.length <= maxLength) full
    else {
      // A deep enough path can exceed common filesystem filename
      // limits; fall back to a hash suffix only once that's a risk,
      // rather than hashing (and losing traceability) for every path.
      val hash = Integer.toHexString(ipynbPath.toString.hashCode)
      val suffix = s"-$hash-$versionPart"
      s"metals-${sanitize(ipynbPath.toString).take(maxLength - 7 - suffix.length)}$suffix"
    }
  }

  private val maxLength = 200

  // Without escaping literal underscores first, "a/b" and "a_b" would
  // both sanitize to "a_b" and collide.
  private def sanitize(s: String): String =
    s.replace("_", "__").replaceAll("[^A-Za-z0-9_-]", "_")
}
