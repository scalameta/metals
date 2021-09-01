package scala.meta.internal.metals

import java.net.URI
import java.nio.file.Paths

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

import com.google.gson.JsonPrimitive
import org.eclipse.{lsp4j => l}

class TastyHandler(
    compilers: Compilers,
    buildTargets: BuildTargets,
    languageClient: MetalsLanguageClient,
    clientConfig: ClientConfiguration
)(implicit ec: ExecutionContext) {

  def executeShowTastyCommand(
      params: l.ExecuteCommandParams
  ): Future[Unit] = {
    val tastyResponseOpt = getTasty(params)
    tastyResponseOpt.map { tastyOpt =>
      val commandOpt = tastyOpt.map { tasty =>
        new l.ExecuteCommandParams(
          "metals-show-tasty",
          List[Object](tasty).asJava
        )
      }
      commandOpt.foreach { command =>
        languageClient.metalsExecuteClientCommand(command)
        scribe.debug(s"Executing show TASTy ${command}")
      }
    }
  }

  def getTastyForURI(uri: URI): Future[Option[String]] =
    getTasty(uri, clientConfig.isCommandInHtmlSupported())

  private def getTasty(
      params: l.ExecuteCommandParams
  ): Future[Option[String]] =
    parseJsonParams(params) match {
      case Some(path) =>
        val uri = Paths.get(path).toUri
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
      isHtmlSupported: Boolean
  ): Future[Option[String]] = {
    val pcAndTargetUriOpt = for {
      buildTargetId <- buildTargets.inverseSources(
        AbsolutePath.fromAbsoluteUri(uri)
      )
      buildTarget <- buildTargets.scalaTarget(buildTargetId)
      pc <- compilers.loadCompilerForTarget(buildTarget)
      tastyFileURI <- getTastyFileURI(
        uri,
        buildTarget.scalac.getClassDirectory.toAbsolutePath
      )
    } yield (pc, tastyFileURI)

    pcAndTargetUriOpt match {
      case Some((pc, tastyUri)) =>
        pc.getTasty(tastyUri, isHtmlSupported).asScala.map(_.asScala)
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
