package scala.meta.internal.metals.notebook

import java.util.Locale

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.control.NonFatal

import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.notebooks.AlmondKernelInstaller
import scala.meta.internal.mtags.MD5
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
  ): Future[Option[NotebookKernel]] = {
    def giveUp(tpe: MessageType, message: String) = {
      if (!askBeforeInstall) languageClient.showMessage(tpe, message)
      Future.successful(None)
    }
    val result = notebookProvider.bestTarget(ipynbPath) match {
      case None =>
        giveUp(MessageType.Warning, "No build target found for this notebook.")
      case Some(target) =>
        val targetName = buildTargets
          .info(target)
          .map(_.getDisplayName)
          .getOrElse(target.getUri)
        val resolved = for {
          scalaTarget <- buildTargets.scalaTarget(target)
          classpath <- buildTargets.fullClasspath(target, Promise())
        } yield
          for {
            paths <- classpath
            // Not always part of the compile classpath.
            classDirs = buildTargets
              .targetClassDirectories(target)
              .toAbsoluteClasspath
              .toList
            kernel = NotebookKernel(
              kernelIdFor(ipynbPath, almondVersion),
              s"Scala ($targetName, Almond $almondVersion)",
            )
            spec = () =>
              AlmondKernelInstaller.kernelSpec(
                javaHome(),
                almondVersion,
                scalaTarget.scalaVersion,
                (classDirs ++ paths).map(_.toNIO),
                kernel.displayName,
              )
            ensured <- ensure(kernel, spec, askBeforeInstall)
          } yield ensured
        resolved.getOrElse(
          giveUp(
            MessageType.Error,
            s"Could not resolve a classpath for '$targetName'.",
          )
        )
    }
    result.recover { case NonFatal(e) =>
      scribe.error(s"failed to ensure Almond kernel for '$ipynbPath'", e)
      None
    }
  }

  private def ensure(
      kernel: NotebookKernel,
      spec: () => ujson.Obj,
      askBeforeInstall: Boolean,
  ): Future[Option[NotebookKernel]] =
    if (AlmondKernelInstaller.exists(kernel.kernelId))
      Future {
        val current = spec()
        if (!AlmondKernelInstaller.isInstalled(kernel.kernelId, current))
          AlmondKernelInstaller.install(kernel.kernelId, current)
        Option(kernel)
      }.recover { case NonFatal(e) =>
        // The previous kernel is still installed.
        scribe.warn(s"failed to refresh Almond kernel '${kernel.kernelId}'", e)
        Some(kernel)
      }
    else
      for {
        confirmed <-
          if (askBeforeInstall) confirmInstall()
          else Future.successful(true)
        installed <-
          if (confirmed) installNew(kernel, spec)
          else Future.successful(None)
      } yield installed

  private def installNew(
      kernel: NotebookKernel,
      spec: () => ujson.Obj,
  ): Future[Option[NotebookKernel]] =
    Future {
      AlmondKernelInstaller.install(kernel.kernelId, spec())
      Option(kernel.copy(newlyInstalled = true))
    }.recover { case NonFatal(e) =>
      scribe.error(s"failed to install Almond kernel '${kernel.kernelId}'", e)
      languageClient.showMessage(
        MessageType.Error,
        s"Failed to install Jupyter kernel '${kernel.displayName}': ${e.getMessage}",
      )
      None
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

  /**
   * `metals-<file name>-<path hash>-<Almond version>`, lower case because
   * jupyter_client lower-cases kernelspec names. The hash keeps notebooks
   * with the same name apart, and the name has no `-`, so the version
   * can't make two ids collide.
   */
  def kernelIdFor(ipynbPath: AbsolutePath, almondVersion: String): String = {
    val name = lower(ipynbPath.filename.stripSuffix(".ipynb"))
      .replaceAll("[^a-z0-9]", "_")
      .take(50)
    val hash = lower(MD5.compute(ipynbPath.toString)).take(16)
    val version = lower(almondVersion).replaceAll("[^a-z0-9.-]", "_")
    s"metals-$name-$hash-$version"
  }

  private def lower(s: String): String = s.toLowerCase(Locale.ROOT)
}
