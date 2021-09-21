package scala.meta.internal.metals

import java.net.URI

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ServerCommands.TastyResponse
import scala.meta.io.AbsolutePath

import org.eclipse.{lsp4j => l}

/**
 * For clients supporting executing commands [[TastyResponse]] is returned and clients can determine on their own how to handle returned value.
 * If client supports http, he is redirected to the tasty endpoint defined at [[MetalsHttpServer]].
 * That endpoint reuses logic declared in [[TastyHandler]]
 * In both cases logic is pretty same:
 * - for a given URI (which could be .scala or .tasty file itself) try to find .tasty file
 * - dispatch request to the Presentation Compiler. It's worth noting that PC takes into account
 *   client configuration to determine proper response format (HTML, console or plain text)
 */
class TastyHandler(
    compilers: Compilers,
    buildTargets: BuildTargets,
    languageClient: MetalsLanguageClient,
    clientConfig: ClientConfiguration,
    httpServer: () => Option[MetalsHttpServer]
)(implicit ec: ExecutionContext) {

  def executeShowTastyCommand(
      path: String
  ): Future[Unit] =
    if (
      clientConfig.isExecuteClientCommandProvider() && !clientConfig
        .isHttpEnabled()
    ) {
      val tastyResponse = getTasty(path)
      tastyResponse.map { tasty =>
        val command = new l.ExecuteCommandParams(
          "metals-show-tasty",
          List[Object](tasty).asJava
        )
        languageClient.metalsExecuteClientCommand(command)
        scribe.debug(s"Executing show TASTy ${command}")
      }
    } else {
      httpServer() match {
        case Some(server) =>
          Future.successful(
            Urls.openBrowser(server.address + s"/tasty?file=$path")
          )
        case None =>
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

  def getTastyForURI(uri: URI): Future[Either[String, String]] =
    getTasty(
      uri,
      clientConfig.isCommandInHtmlSupported(),
      clientConfig.isHttpEnabled()
    )

  private def getTasty(
      path: String
  ): Future[TastyResponse] = {
    val uri = new URI(path)
    getTastyForURI(uri).map { result =>
      TastyResponse(
        uri,
        result.fold(_ => null, identity),
        result.fold(identity, _ => null)
      )
    }
  }

  /**
   * For a given uri (could be both .scala and .tasty file) try to find:
   * - build target
   * - uri to existing tasty file
   * If they both exists get decoded tasty file content (pc.getTasty takes into account if client supports html)
   */
  private def getTasty(
      uri: URI,
      isHtmlSupported: Boolean,
      isHttpEnabled: Boolean
  ): Future[Either[String, String]] = {
    val absolutePath = Try(AbsolutePath.fromAbsoluteUri(uri)) match {
      case Success(value) => Right(value)
      case Failure(exception) =>
        val error = exception.toString
        scribe.error(error)
        Left(error)
    }

    val pcAndTargetUri = for {
      filePath <- absolutePath
      buildTarget <-
        buildTargets
          .inverseSources(filePath)
          .flatMap { buildTargetId => buildTargets.scalaTarget(buildTargetId) }
          .toRight(s"Cannot find build target for $uri")
          .filterOrElse(
            _.scalaInfo.getScalaVersion.startsWith("3."),
            """Currently, there is no support for the "Show TASTy" feature in Scala 2."""
          )
      pc <- compilers
        .loadCompilerForTarget(buildTarget)
        .toRight("Cannot load presentation compiler")
      tastyFileURI <- getTastyFileURI(
        uri,
        buildTarget.scalac.getClassDirectory.toAbsolutePath
      )
    } yield (pc, tastyFileURI)

    pcAndTargetUri match {
      case Right((pc, tastyUri)) =>
        pc.getTasty(tastyUri, isHtmlSupported, isHttpEnabled)
          .asScala
          .map(Right(_))
      case Left(error) =>
        Future.successful(Left(error))
    }
  }

  /**
   * @param classDir class directory of build target where .tasty file will be looked for
   * @return Right with tasty file for given uri exists or Left with potential error otherwise
   */
  private def getTastyFileURI(
      uri: URI,
      classDir: AbsolutePath
  ): Either[String, URI] = {
    val filePath = AbsolutePath.fromAbsoluteUri(uri)
    val filePathString = filePath.toString
    val tastyURI =
      if (filePathString.endsWith(".tasty")) {
        Right(filePath.toURI)
      } else if (filePathString.endsWith(".scala")) {
        val fileSourceDirOpt = buildTargets.inverseSourceItem(filePath)
        fileSourceDirOpt
          .map { fileSourceDir =>
            val relative = filePath
              .toRelative(fileSourceDir)
              .resolveSibling(p => p.stripSuffix(".scala") + ".tasty")
            classDir.resolve(relative).toURI
          }
          .toRight("Cannot find directory with compiled classes")
      } else Left(s"$uri has incorrect file extension")

    tastyURI.filterOrElse(
      AbsolutePath.fromAbsoluteUri(_).isFile,
      s"There is no .tasty file for $uri at $classDir}"
    )
  }
}
