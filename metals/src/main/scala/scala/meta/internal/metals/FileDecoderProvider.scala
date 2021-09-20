package scala.meta.internal.metals

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import javax.annotation.Nullable

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Properties
import scala.util.Success
import scala.util.Try
import scala.util.control.NonFatal

import scala.meta.cli.Reporter
import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.io.FileIO
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metap.Main
import scala.meta.internal.mtags.SemanticdbClasspath
import scala.meta.io.AbsolutePath
import scala.meta.metap.Format
import scala.meta.metap.Settings
import scala.meta.pc.PresentationCompiler

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import org.eclipse.{lsp4j => l}

/* Response which is sent to the lsp client. Because of java serialization we cannot use
 * sealed hierarchy to model union type of success and error.
 * Moreover, we cannot use Option to indicate optional values, so instead every field is nullable.
 * */
final case class DecoderResponse(
    @Nullable requestedUri: String,
    @Nullable value: String,
    @Nullable error: String
)

final class FileDecoderProvider(
    workspace: AbsolutePath,
    compilers: Compilers,
    buildTargets: BuildTargets,
    userConfig: () => UserConfiguration,
    shellRunner: ShellRunner,
    fileSystemSemanticdbs: FileSystemSemanticdbs,
    languageClient: MetalsLanguageClient,
    clientConfig: ClientConfiguration,
    httpServer: () => Option[MetalsHttpServer]
)(implicit ec: ExecutionContext) {

  private case class PathInfo(
      targetId: Option[BuildTargetIdentifier],
      path: AbsolutePath
  )
  private case class Finder(findFile: String => Option[PathInfo])
  private case class Decoder(decode: PathInfo => Future[Option[String]])

  /**
   * URI format...
   * metalsDecode:/fileUrl?decoder=decoderName&keyValueArguments
   * jar:file:///jarPath/jar-sources.jar!/packagedir/file.java
   *
   * Examples...
   * metalsDecode:file:///somePath/someFile.java.javap-verbose?decoder=javap&verbose=true
   * metalsDecode:file:///somePath/someFile.scala.javap-verbose?decoder=javap&verbose=true
   * metalsDecode:file:///somePath/someFile.class.javap-verbose?decoder=javap&verbose=true
   *
   * metalsDecode:file:///somePath/someFile.java.javap?decoder=java
   * metalsDecode:file:///somePath/someFile.scala.javap?decoder=javap
   * metalsDecode:file:///somePath/someFile.class.javap?decoder=javap
   *
   * metalsDecode:file:///somePath/someFile.java.semanticdb-compact?decoder=semanticdb&format=compact
   * metalsDecode:file:///somePath/someFile.java.semanticdb-detailed?decoder=semanticdb
   *
   * metalsDecode:file:///somePath/someFile.scala.semanticdb-compact?decoder=semanticdb&format=compact
   * metalsDecode:file:///somePath/someFile.scala.semanticdb-detailed?decoder=semanticdb
   *
   * metalsDecode:file:///somePath/someFile.java.semanticdb.semanticdb-compact?decoder=semanticdb&format=compact
   * metalsDecode:file:///somePath/someFile.java.semanticdb.semanticdb-detailed?decoder=semanticdb
   *
   * metalsDecode:file:///somePath/someFile.scala.semanticdb.semanticdb-compact?decoder=semanticdb&format=compact
   * metalsDecode:file:///somePath/someFile.scala.semanticdb.semanticdb-detailed?decoder=semanticdb
   *
   * metalsDecode:file:///somePath/someFile.scala.tasty-detailed?decoder=tasty
   * metalsDecode:file:///somePath/someFile.tasty.tasty-detailed?decoder=tasty
   *
   * jar:file:///somePath/someFile-sources.jar!/somePackage/someFile.java
   */
  def decodedFileContents(uriAsStr: String): Future[DecoderResponse] = {
    for {
      check <- Future { getDecodeInfo(uriAsStr) }
      output <- check match {
        case None => Future.successful(None)
        case Some((decoder, path)) => decoder.decode(path)
      }
      checkedOutput = output match {
        case None => errorReponse(uriAsStr)
        case Some(success) => DecoderResponse(uriAsStr, success, null)
      }
    } yield checkedOutput
  }

  def errorReponse(uriAsStr: String): DecoderResponse =
    DecoderResponse(uriAsStr, null, errorMessage(uriAsStr))

  private def errorMessage(input: String): String =
    s"""|$input
        |
        |Unexpected uri, Metals accepts ones such as:
        |
        |metalsDecode:file:///somedir/someFile.scala.javap-verbose?decoder=javap
        |
        |Take a look at scala/meta/internal/metals/FileDecoderProvider for more information.
        |
        |Or wait for indexing/compiling to finish and re-try
        |""".stripMargin

  private def getDecodeInfo(
      uriAsStr: String
  ): Option[(Decoder, PathInfo)] = {
    val uri = Try(URI.create(uriAsStr)).toOption
    uri.flatMap(uri =>
      uri.getScheme() match {
        case "jar" => decodeJar(uri)
        case "file" => decodeMetalsFile(uri)
        case "metalsDecode" => getDecodeInfo(uri.getSchemeSpecificPart())
        case _ => None
      }
    )
  }

  private def decodeJar(uri: URI): Option[(Decoder, PathInfo)] = {
    Try {
      // jar file system cannot cope with a heavily encoded uri
      // hence the roundabout way of creating an AbsolutePath
      // must have "jar:file:"" instead of "jar:file%3A"
      val decodedUriStr = URLDecoder.decode(uri.toString(), "UTF-8")
      val decodedUri = URI.create(decodedUriStr)
      AbsolutePath(Paths.get(decodedUri))
    }.toOption
      .map(path =>
        (
          Decoder(path =>
            Future {
              Try(FileIO.slurp(path.path, StandardCharsets.UTF_8)).toOption
            }
          ),
          PathInfo(None, path)
        )
      )
  }

  private def decodeMetalsFile(
      uri: URI
  ): Option[(Decoder, PathInfo)] = {
    for {
      query <- Option(uri.getQuery())
      paramArr = query.split("&").map(_.split("=", 2))
      if (paramArr.forall(_.length == 2))
      params <- Try {
        paramArr
          .map(f => f.map(URLDecoder.decode(_, "UTF-8")))
          .map(f => f(0) -> f(1))
          .toMap
      }.toOption
      if (params.contains("decoder"))
      (finder, decoder) <- getDecoder(params)
      fileToDecode <- finder.findFile(uri.getPath())
    } yield ((decoder, fileToDecode))
  }

  private def getDecoder(
      params: Map[String, String]
  ): Option[(Finder, Decoder)] = {
    params("decoder") match {
      case "javap" => Some(getJavapDecoder(params))
      case "semanticdb" => Some(getSemanticdbDecoder(params))
      case "tasty" => Some(getTastyDecoder(params))
      case _ => None
    }
  }

  private def boolParam(params: Map[String, String], name: String): Boolean =
    params.get(name).map(_.toBoolean).getOrElse(false)

  private def toFile(
      uriPath: String,
      suffixToRemove: String
  ): Option[AbsolutePath] = {
    Try {
      s"file://${uriPath}".stripSuffix(suffixToRemove).toAbsolutePath
    }.toOption.filter(_.exists)
  }

  private def getJavapDecoder(
      params: Map[String, String]
  ): (Finder, Decoder) = {
    val verbose = boolParam(params, "verbose")
    val suffix = if (verbose) ".javap-verbose" else ".javap"
    val finder = Finder(uriPath =>
      for {
        path <- toFile(uriPath, suffix)
        classesPath <-
          if (path.isScalaOrJava) findClassesDirFileFromSource(path, "class")
          else Some(PathInfo(None, path))
      } yield classesPath
    )
    val decoder = Decoder(decodeJavapFromClassFile(_, verbose))
    (finder, decoder)
  }

  private def getSemanticdbDecoder(
      params: Map[String, String]
  ): (Finder, Decoder) = {
    val format = params
      .get("format")
      .map(_.toUpperCase match {
        case "DETAILED" => Format.Detailed
        case "COMPACT" => Format.Compact
        case "PROTO" => Format.Proto
      })
      .getOrElse(Format.Detailed)
    val suffix = format match {
      case Format.Detailed => ".semanticdb-detailed"
      case Format.Compact => ".semanticdb-compact"
      case Format.Proto => ".semanticdb-proto"
    }
    val finder = Finder(uriPath =>
      for {
        path <- toFile(uriPath, suffix)
        semanticPath <-
          if (path.isScalaOrJava) findSemanticDBFileFromSource(path)
          else Some(PathInfo(None, path))
      } yield semanticPath
    )
    val decoder = Decoder(decodeFromSemanticDBFile(_, format))
    (finder, decoder)
  }

  private def getTastyDecoder(
      params: Map[String, String]
  ): (Finder, Decoder) = {
    val finder = Finder(uriPath =>
      for {
        path <- toFile(uriPath, ".tasty-detailed")
        classesPath <-
          if (path.isScalaOrJava) findClassesDirFileFromSource(path, "tasty")
          else findPathInfoFromClassesPath(path)
      } yield classesPath
    )
    val decoder = Decoder(decodeFromTastyFile(_))
    (finder, decoder)
  }

  private def findPathInfoFromClassesPath(
      path: AbsolutePath
  ): Option[PathInfo] = {
    val pathInfos = for {
      scalaTarget <- buildTargets.all
      classPath = scalaTarget.classDirectory.toAbsolutePath
      if (path.isInside(classPath))
    } yield PathInfo(Some(scalaTarget.id), path)
    pathInfos.toList.headOption
  }

  private def findClassesDirFileFromSource(
      sourceFile: AbsolutePath,
      newExtension: String
  ): Option[PathInfo] = {
    for {
      targetId <- buildTargets.sourceBuildTargets(sourceFile).headOption
      target <- buildTargets.scalaTarget(targetId)
      sourceRoot <- buildTargets.inverseSourceItem(sourceFile)
      classDir = target.classDirectory.toAbsolutePath
      oldExtension = sourceFile.extension
      relativePath = sourceFile
        .toRelative(sourceRoot)
        .resolveSibling(_.stripSuffix(oldExtension) + newExtension)
    } yield PathInfo(Some(targetId), classDir.resolve(relativePath))
  }
  private def findSemanticDBFileFromSource(
      sourceFile: AbsolutePath
  ): Option[PathInfo] = {
    for {
      targetId <- buildTargets.inverseSources(sourceFile)
      target <- buildTargets.scalaTarget(targetId)
      sourceRoot <- buildTargets.workspaceDirectory(targetId)
      targetRoot = target.targetroot
      relativePath = SemanticdbClasspath.fromScala(
        sourceFile.toRelative(sourceRoot.dealias)
      )
      foundSemanticDbPath <- fileSystemSemanticdbs.findSemanticDb(
        relativePath,
        targetRoot,
        sourceFile,
        workspace
      )
    } yield PathInfo(Some(targetId), foundSemanticDbPath.path)
  }

  private def decodeJavapFromClassFile(
      pathInfo: PathInfo,
      verbose: Boolean
  ): Future[Option[String]] = {
    try {
      val args = if (verbose) List("-verbose") else Nil
      val sb = new StringBuilder()
      shellRunner
        .run(
          "Decode using javap",
          JavaBinary(userConfig().javaHome, "javap") :: args ::: List(
            pathInfo.path.filename
          ),
          pathInfo.path.parent,
          redirectErrorOutput = true,
          Map.empty,
          s => {
            sb.append(s)
            sb.append(Properties.lineSeparator)
          },
          s => (),
          propagateError = true,
          logInfo = false
        )
        .map(_ => Some(sb.toString))
    } catch {
      case NonFatal(_) => Future.successful(None)
    }
  }

  private def decodeFromSemanticDBFile(
      pathInfo: PathInfo,
      format: Format
  ): Future[Option[String]] = {
    Future {
      Try {
        val out = new ByteArrayOutputStream()
        val err = new ByteArrayOutputStream()
        val psOut = new PrintStream(out)
        val psErr = new PrintStream(err)
        try {
          val reporter =
            Reporter().withOut(psOut).withErr(psErr)
          val settings =
            Settings()
              .withPaths(List(pathInfo.path.toNIO))
              .withFormat(format)
          val main = new Main(settings, reporter)
          main.process()
          val output = new String(out.toByteArray);
          val error = new String(err.toByteArray);
          if (error.isEmpty)
            output
          else
            error
        } finally {
          psOut.close()
          psErr.close()
        }
      }.toOption
    }
  }

  private def decodeFromTastyFile(
      pathInfo: PathInfo
  ): Future[Option[String]] =
    loadPresentationCompiler(pathInfo) match {
      case Some(pc) =>
        pc.getTasty(pathInfo.path.toURI, false, false).asScala.map(Some(_))
      case None =>
        Future.successful(None)
    }

  private def loadPresentationCompiler(
      pathInfo: PathInfo
  ): Option[PresentationCompiler] =
    for {
      targetId <- pathInfo.targetId
      pc <- compilers.loadCompiler(targetId)
    } yield pc

  /**
   * For clients supporting executing commands [[TastyResponse]] is returned and clients can determine on their own how to handle returned value.
   * If client supports http, he is redirected to the tasty endpoint defined at [[MetalsHttpServer]].
   * That endpoint reuses logic declared in [[TastyHandler]]
   * In both cases logic is pretty same:
   * - for a given URI (which could be .scala or .tasty file itself) try to find .tasty file
   * - dispatch request to the Presentation Compiler. It's worth noting that PC takes into account
   *   client configuration to determine proper response format (HTML, console or plain text)
   */
  def executeShowTastyCommand(params: TextDocumentPositionParams): Future[Unit] =
    if (
      clientConfig.isExecuteClientCommandProvider() && !clientConfig
        .isHttpEnabled()
    ) {
      val response = getTastyForURI(uri).map { result =>
        DecoderResponse(
          uri.toString(),
          result.fold(_ => null, identity),
          result.fold(identity, _ => null)
        )
      }
      response.map { tasty =>
        val command = new l.ExecuteCommandParams(
          "metals-show-tasty",
          List[Object](tasty).asJava
        )
        languageClient.metalsExecuteClientCommand(command)
        scribe.debug(s"Executing show TASTy $command")
      }
    } else
      httpServer() match {
        case Some(server) =>
          Future.successful(
            Urls.openBrowser(server.address + s"/tasty?file=$uri")
          )
        case None =>
          Future.successful {
            scribe.warn(
              "Unable to run show tasty. Make sure `isHttpEnabled` is set to `true`."
            )
          }
      }

  def getTastyForURI(uri: URI): Future[Either[String, String]] = {
    val error = s"Can't find existing build target for $uri"
    val pcAndTargetUri =
      for {
        path <- Try(AbsolutePath.fromAbsoluteUri(uri)) match {
          case Success(path) if !path.isFile => Left(s"$uri doesn't exist")
          case Success(path) if path.isFile => Right(path)
          case Failure(_) => Left(s"$uri has to be absolute")
        }
        pathInfo <-
          if (path.isScala)
            findClassesDirFileFromSource(path, "tasty").toRight(error)
          else if (path.extension == "tasty")
            findPathInfoFromClassesPath(path).toRight(error)
          else Left(s"$uri has incorrect file extension")
        _ <- pathInfo.targetId
          .flatMap(buildTargets.scalaTarget)
          .map(_.scalaInfo.getScalaVersion())
          .filter(_.startsWith("3."))
          .toRight(
            """Currently, there is no support for the "Show TASTy" feature in Scala 2."""
          )
        pc <- loadPresentationCompiler(pathInfo).toRight(
          s"Can't load presentation compiler for $uri"
        )
      } yield (pc, pathInfo.path.toURI)

    pcAndTargetUri match {
      case Right((pc, tastyUri)) =>
        pc.getTasty(
          tastyUri,
          clientConfig.isCommandInHtmlSupported(),
          clientConfig.isHttpEnabled()
        ).asScala
          .map(Right(_))
      case Left(error) =>
        Future.successful(Left(error))
    }
  }
}
