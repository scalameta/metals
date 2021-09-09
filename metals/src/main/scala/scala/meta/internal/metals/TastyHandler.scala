package scala.meta.internal.metals

import java.net.URI
import java.nio.file.Paths

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

import com.google.gson.JsonPrimitive
import org.eclipse.{lsp4j => l}

class TastyHandler(
    compilers: Compilers,
    buildTargets: BuildTargets,
    languageClient: MetalsLanguageClient,
    clientConfig: ClientConfiguration,
    httpServer: () => Option[MetalsHttpServer]
)(implicit ec: ExecutionContext) {

  def executeShowTastyCommand(
      params: l.ExecuteCommandParams
  ): Future[Unit] =
    if (
      clientConfig.isExecuteClientCommandProvider() && !clientConfig
        .isHttpEnabled()
    ) {
      val tastyResponse = getTasty(params)
      tastyResponse.map {
        case Some(tasty) =>
          val command = new l.ExecuteCommandParams(
            "metals-show-tasty",
            List[Object](tasty).asJava
          )
          languageClient.metalsExecuteClientCommand(command)
          scribe.debug(s"Executing show TASTy ${command}")
        case None =>
          scribe.error(s"Show TASTy command failed")
          languageClient.showMessage(
            Messages.showTastyFailed
          )
      }
    } else {
      (httpServer(), parseJsonParams(params)) match {
        case (Some(server), Some(uri)) =>
          Future.successful(
            Urls.openBrowser(server.address + s"/tasty?file=$uri")
          )
        case (None, _) =>
          Future.successful {
            scribe.warn(
              "Unable to run show tasty. Make sure `isHttpEnabled` is set to `true`."
            )
          }
        case other =>
          scribe.error(s"Show TASTy command failed $other")
          languageClient.showMessage(
            Messages.showTastyFailed
          )
          Future.successful(())
      }
    }

  def getTastyForURI(uri: URI): Future[Option[String]] =
    getTasty(
      uri,
      clientConfig.isCommandInHtmlSupported(),
      clientConfig.isHttpEnabled()
    )

  private def getTasty(
      params: l.ExecuteCommandParams
  ): Future[Option[String]] =
    parseJsonParams(params) match {
      case Some(path) =>
        val uri = new URI(path)
        getTastyForURI(uri)
      case None =>
        Future.successful(None)
    }

  private def parseJsonParams(
      commandParams: l.ExecuteCommandParams
  ): Option[String] = for {
    args <- Option(commandParams.getArguments)
    arg <- args.asScala.headOption.collect {
      case js: JsonPrimitive if js.isString => js
    }
  } yield arg.getAsString()

  /**
   * For given uri (could be both .scala and .tasty file) try to find:
   * - build target
   * - uri to existing tasty file
   * Iff they both exists get decoded tasty file content (pc.getTasty takes into account if client supports html)
   */
  private def getTasty(
      uri: URI,
      isHtmlSupported: Boolean,
      isHttpEnabled: Boolean
  ): Future[Option[String]] = {
    val absolutePathOpt = Try(AbsolutePath.fromAbsoluteUri(uri)) match {
      case Success(value) => Some(value)
      case Failure(exception) =>
        scribe.error(exception.toString())
        None
    }
    val pcAndTargetUriOpt = for {
      absolutePath <- absolutePathOpt
      buildTargetId <- buildTargets.inverseSources(absolutePath)
      buildTarget <- buildTargets.scalaTarget(buildTargetId)
      pc <- compilers.loadCompilerForTarget(buildTarget)
      tastyFileURI <- getTastyFileURI(
        uri,
        buildTarget.scalac.getClassDirectory.toAbsolutePath
      )
    } yield (pc, tastyFileURI)

    pcAndTargetUriOpt match {
      case Some((pc, tastyUri)) =>
        pc.getTasty(tastyUri, isHtmlSupported, isHttpEnabled)
          .asScala
          .map(_.asScala)
      case _ => Future.successful(None)
    }
  }

  /**
   * @param classDir class directory of build target where .tasty file will be looked for
   * @return Some(tastyURI) when tasty file for given uri exists or None otherwise
   */
  private def getTastyFileURI(
      uri: URI,
      classDir: AbsolutePath
  ): Option[URI] = {
    val filePath = AbsolutePath.fromAbsoluteUri(uri)
    val filePathString = filePath.toString
    val tastyURIOpt =
      if (filePathString.endsWith(".tasty")) {
        Some(filePath.toURI)
      } else if (filePathString.endsWith(".scala")) {
        val fileSourceDirOpt = buildTargets.inverseSourceItem(filePath)
        fileSourceDirOpt.map { fileSourceDir =>
          val relative = filePath
            .toRelative(fileSourceDir)
            .resolveSibling(p => p.stripSuffix(".scala") + ".tasty")
          classDir.resolve(relative).toURI
        }
      } else None

    tastyURIOpt.filter(AbsolutePath.fromAbsoluteUri(_).isFile)
  }
}
