package scala.meta.internal.metals

import java.net.URI

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

import com.google.gson.JsonPrimitive
import org.eclipse.{lsp4j => l}

// response which is send to the lsp client. Because of java serialization we cannot use
// sealed hierarchy to model union type of success and error.
// Moreover, we canot also use Option, so instead every field is nullable
private case class TastyResponse(
    requestedUri: URI,
    tasty: String,
    error: String
)

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
      tastyResponse.map { tasty =>
        val command = new l.ExecuteCommandParams(
          "metals-show-tasty",
          List[Object](tasty).asJava
        )
        languageClient.metalsExecuteClientCommand(command)
        scribe.debug(s"Executing show TASTy ${command}")
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

  def getTastyForURI(uri: URI): Future[Either[String, String]] =
    getTasty(
      uri,
      clientConfig.isCommandInHtmlSupported(),
      clientConfig.isHttpEnabled()
    )

  private def getTasty(
      params: l.ExecuteCommandParams
  ): Future[TastyResponse] =
    parseJsonParams(params) match {
      case Some(path) =>
        val uri = new URI(path)
        getTastyForURI(uri).map { result =>
          TastyResponse(
            uri,
            result.fold(_ => null, identity),
            result.fold(identity, _ => null)
          )
        }
      case None =>
        val error = s"Error, invalid show TASTy arguments $params"
        scribe.error(error)
        Future.successful(TastyResponse(null, null, error))
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
  ): Future[Either[String, String]] = {
    val absolutePathOpt = Try(AbsolutePath.fromAbsoluteUri(uri)) match {
      case Success(value) => Right(value)
      case Failure(exception) =>
        val error = exception.toString
        scribe.error(error)
        Left(error)
    }

    val pcAndTargetUriOpt = for {
      absolutePath <- absolutePathOpt
      buildTarget <-
        buildTargets
          .inverseSources(absolutePath)
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

    pcAndTargetUriOpt match {
      case Right((pc, tastyUri)) =>
        pc.getTasty(tastyUri, isHtmlSupported, isHttpEnabled)
          .asScala
          .map(Right(_))
      case Left(error) => Future.successful(Left(error))
    }
  }

  /**
   * @param classDir class directory of build target where .tasty file will be looked for
   * @return Some(tastyURI) when tasty file for given uri exists or None otherwise
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
