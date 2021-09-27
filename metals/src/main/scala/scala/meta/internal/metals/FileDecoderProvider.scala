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
import scala.meta.internal.parsing.ClassFinder
import scala.meta.io.AbsolutePath
import scala.meta.metap.Format
import scala.meta.metap.Settings
import scala.meta.pc.PresentationCompiler

import ch.epfl.scala.bsp4j.BuildTargetIdentifier

/* Response which is sent to the lsp client. Because of java serialization we cannot use
 * sealed hierarchy to model union type of success and error.
 * Moreover, we cannot use Option to indicate optional values, so instead every field is nullable.
 * */
final case class DecoderResponse(
    requestedUri: String,
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
    classFinder: ClassFinder
)(implicit ec: ExecutionContext) {

  private case class PathInfo(
      targetId: Option[BuildTargetIdentifier],
      path: AbsolutePath
  )
  private case class Finder(findFile: String => Future[Option[PathInfo]])

  sealed trait DecoderError {
    def toDecoderResponse(uri: String): DecoderResponse = this match {
      case Cancelled => DecoderResponse(uri, null, null)
      case Failed(error) => DecoderResponse(uri, null, error)
    }
  }
  private case object Cancelled extends DecoderError
  private case class Failed(error: String) extends DecoderError

  private case class Decoder(
      decode: PathInfo => Future[Either[DecoderError, String]]
  )

  /**
   * URI format...
   * metalsDecode:/fileUrl.decodeExtension
   * or
   * jar:file:///jarPath/jar-sources.jar!/packagedir/file.java
   *
   * Examples...
   * javap:
   * metalsDecode:file:///somePath/someFile.java.javap
   * metalsDecode:file:///somePath/someFile.scala.javap
   * metalsDecode:file:///somePath/someFile.class.javap
   *
   * metalsDecode:file:///somePath/someFile.java.javap-verbose
   * metalsDecode:file:///somePath/someFile.scala.javap-verbose
   * metalsDecode:file:///somePath/someFile.class.javap-verbose
   *
   * semanticdb:
   * metalsDecode:file:///somePath/someFile.java.semanticdb-compact
   * metalsDecode:file:///somePath/someFile.java.semanticdb-detailed
   *
   * metalsDecode:file:///somePath/someFile.scala.semanticdb-compact
   * metalsDecode:file:///somePath/someFile.scala.semanticdb-detailed
   *
   * metalsDecode:file:///somePath/someFile.java.semanticdb.semanticdb-compact
   * metalsDecode:file:///somePath/someFile.java.semanticdb.semanticdb-detailed
   *
   * metalsDecode:file:///somePath/someFile.scala.semanticdb.semanticdb-compact
   * metalsDecode:file:///somePath/someFile.scala.semanticdb.semanticdb-detailed
   *
   * tasty:
   * metalsDecode:file:///somePath/someFile.scala.tasty-decoded
   * metalsDecode:file:///somePath/someFile.tasty.tasty-decoded
   *
   * jar:
   * metalsDecode:jar:file:///somePath/someFile-sources.jar!/somePackage/someFile.java
   */
  def decodedFileContents(uriAsStr: String): Future[DecoderResponse] = {
    for {
      check <- getDecodeInfo(uriAsStr)
      output <- check match {
        case Left(error) => Future.successful(Left(error))
        case Right((decoder, path)) => decoder.decode(path)
      }
    } yield output match {
      case Left(error) => error.toDecoderResponse(uriAsStr)
      case Right(decoded) => DecoderResponse(uriAsStr, decoded, null)
    }
  }

  def errorResponse(uriAsStr: String): DecoderResponse =
    DecoderResponse(uriAsStr, null, errorMessage(uriAsStr))

  private def errorMessage(input: String = ""): String =
    s"""|$input
        |
        |Unexpected uri, Metals accepts ones such as:
        |
        |metalsDecode:file:///somedir/someFile.scala.javap-verbose
        |
        |Take a look at scala/meta/internal/metals/FileDecoderProvider for more information.
        |
        |Or wait for indexing/compiling to finish and re-try
        |""".stripMargin

  private def getDecodeInfo(
      uriAsStr: String
  ): Future[Either[DecoderError, (Decoder, PathInfo)]] =
    Try(URI.create(uriAsStr)) match {
      case Success(uri) =>
        uri.getScheme() match {
          case "jar" => Future { decodeJar(uri) }
          case "file" => decodeMetalsFile(uri)
          case "metalsDecode" => getDecodeInfo(uri.getSchemeSpecificPart())
          case _ => Future.successful(Left(Failed(errorMessage())))
        }
      case Failure(_) =>
        Future.successful(
          Left(Failed(s"Couldn't create an URI from the $uriAsStr"))
        )
    }

  private def decodeJar(uri: URI): Either[DecoderError, (Decoder, PathInfo)] =
    Try {
      // jar file system cannot cope with a heavily encoded uri
      // hence the roundabout way of creating an AbsolutePath
      // must have "jar:file:"" instead of "jar:file%3A"
      val decodedUriStr = URLDecoder.decode(uri.toString(), "UTF-8")
      val decodedUri = URI.create(decodedUriStr)
      AbsolutePath(Paths.get(decodedUri))
    }.toEitherWith(t => Failed(t.toString()))
      .map(path =>
        (
          Decoder(path =>
            Future {
              Try(FileIO.slurp(path.path, StandardCharsets.UTF_8))
                .toEitherWith(t => Failed(t.toString()))
            }
          ),
          PathInfo(None, path)
        )
      )

  private def decodeMetalsFile(
      uri: URI
  ): Future[Either[DecoderError, (Decoder, PathInfo)]] = {
    val decoder: Option[(Finder, Decoder)] = {
      val additionalExtension = uri.toString().split('.').toList.last
      additionalExtension match {
        case "javap" =>
          Some(getJavapDecoder(isVerbose = false))
        case "javap-verbose" =>
          Some(getJavapDecoder(isVerbose = true))
        case "tasty-decoded" =>
          Some(getTastyDecoder())
        case "semanticdb-compact" =>
          Some(getSemanticdbDecoder(Format.Compact))
        case "semanticdb-detailed" =>
          Some(getSemanticdbDecoder(Format.Detailed))
        case "semanticdb-proto" =>
          Some(getSemanticdbDecoder(Format.Proto))
        case _ => None
      }
    }

    decoder match {
      case Some((finder, decoder)) =>
        finder
          .findFile(uri.getPath())
          .map {
            _.toRight(Failed(s"Couldn't find ${uri.toString()}"))
              .map(fileToDecode => (decoder, fileToDecode))
          }
      case None =>
        Future.successful(Left(Failed(s"URI $uri has unsupported extension")))
    }
  }

  private def toFile(
      uriPath: String,
      suffixToRemove: String
  ): Option[AbsolutePath] = Try {
    s"file://${uriPath}".stripSuffix(suffixToRemove).toAbsolutePath
  }.toOption.filter(_.exists)

  private def getJavapDecoder(
      isVerbose: Boolean
  ): (Finder, Decoder) = {
    val suffix = if (isVerbose) ".javap-verbose" else ".javap"
    val finder = Finder { uriPath =>
      toFile(uriPath, suffix) match {
        case Some(path) =>
          if (path.isClassfile) Future { Some(PathInfo(None, path)) }
          else if (path.isJava) Future {
            findPathInfoFromSource(path, ".class")
          }
          else if (path.isScala)
            findPathInfoForScalaFile(path, true).map(_.toOption)
          else Future.successful(None)
        case None => Future.successful(None)
      }
    }
    val decoder = Decoder(decodeJavapFromClassFile(_, isVerbose))
    (finder, decoder)
  }

  private def getSemanticdbDecoder(
      format: Format
  ): (Finder, Decoder) = {
    val suffix = format match {
      case Format.Detailed => ".semanticdb-detailed"
      case Format.Compact => ".semanticdb-compact"
      case Format.Proto => ".semanticdb-proto"
    }
    val finder = Finder(uriPath =>
      Future {
        toFile(uriPath, suffix).flatMap { path =>
          if (path.isScalaOrJava) findSemanticDbPathInfo(path)
          else Some(PathInfo(None, path))
        }
      }
    )
    val decoder = Decoder(decodeFromSemanticDBFile(_, format))
    (finder, decoder)
  }

  private def getTastyDecoder(): (Finder, Decoder) = {
    val finder = Finder(uriPath =>
      toFile(uriPath, ".tasty-decoded") match {
        case Some(path) =>
          if (path.isScala)
            findPathInfoForScalaFile(path, false).map(_.toOption)
          else if (path.isTasty) Future { findPathInfoForClassesPathFile(path) }
          else Future.successful(None)
        case None =>
          Future.successful(None)
      }
    )
    val decoder = Decoder(decodeFromTastyFile(_))
    (finder, decoder)
  }

  private def findPathInfoFromSource(
      sourceFile: AbsolutePath,
      newExtension: String
  ): Option[PathInfo] = {
    for {
      (targetId, target, sourceRoot) <- findBuildTargetMetadata(sourceFile)
      classDir = target.classDirectory.toAbsolutePath
      oldExtension = sourceFile.extension
      relativePath = sourceFile
        .toRelative(sourceRoot)
        .resolveSibling(_.stripSuffix(oldExtension) + newExtension)
    } yield PathInfo(Some(targetId), classDir.resolve(relativePath))
  }

  private def findPathInfoForClassesPathFile(
      path: AbsolutePath
  ): Option[PathInfo] = {
    val pathInfos = for {
      scalaTarget <- buildTargets.all
      classPath = scalaTarget.classDirectory.toAbsolutePath
      if (path.isInside(classPath))
    } yield PathInfo(Some(scalaTarget.id), path)
    pathInfos.toList.headOption
  }

  /**
   * For a given scala file find all definitions (such as classes, traits, object and toplevel package definition) which
   * may produce .class or .tasty file.
   * If there is more than one candidate asks user, using lsp client and quickpick, which class he wants to decode.
   * If there is only one possible candidate then just pick it.
   *
   * @param includeInnerClasses - if true searches for candidates which produce .class file, otherwise .tasty
   */
  private def findPathInfoForScalaFile(
      path: AbsolutePath,
      includeInnerClasses: Boolean
  ): Future[Either[DecoderError, PathInfo]] = {
    val availableClasses = classFinder
      .findAllClasses(path, includeInnerClasses)
    availableClasses match {
      case Some(classes) if classes.nonEmpty =>
        val resourceToDecode =
          if (classes.size > 1) {
            val quickPickParams = MetalsQuickPickParams(
              classes
                .map(c =>
                  MetalsQuickPickItem(c.path, c.friendlyName, c.description)
                )
                .asJava,
              placeHolder = "Pick the class you want to decode"
            )
            languageClient.metalsQuickPick(quickPickParams).asScala.map {
              result =>
                if (result.cancelled != null && result.cancelled)
                  Left(Cancelled)
                else Right(result.itemId)
            }
          } else
            Future.successful(Right(classes.head.path))
        resourceToDecode.map { resource =>
          resource.flatMap { resourcePath =>
            val pathInfoOpt = for {
              (targetId, target, sourceRoot) <- findBuildTargetMetadata(path)
            } yield {
              val classDir = target.classDirectory.toAbsolutePath
              val pathToResource = classDir.resolve(resourcePath)
              PathInfo(Some(targetId), pathToResource)
            }
            pathInfoOpt.toRight(
              Failed(s"Cannot find a build target for ${path.toURI.toString}")
            )
          }
        }
      case _ =>
        Future.successful(
          Left(Failed("File doesn't contain any toplevel definitions"))
        )
    }
  }

  private def findSemanticDbPathInfo(
      sourceFile: AbsolutePath
  ): Option[PathInfo] =
    for {
      (targetId, target, sourceRoot) <- findBuildTargetMetadata(sourceFile)
      foundSemanticDbPath <- {
        val targetRoot = target.targetroot
        val relativePath = SemanticdbClasspath.fromScala(
          sourceFile.toRelative(sourceRoot.dealias)
        )
        fileSystemSemanticdbs.findSemanticDb(
          relativePath,
          targetRoot,
          sourceFile,
          workspace
        )
      }
    } yield PathInfo(Some(targetId), foundSemanticDbPath.path)

  private def findBuildTargetMetadata(
      sourceFile: AbsolutePath
  ): Option[(BuildTargetIdentifier, ScalaTarget, AbsolutePath)] =
    for {
      targetId <- buildTargets.inverseSources(sourceFile)
      target <- buildTargets.scalaTarget(targetId)
      sourceRoot <- buildTargets.workspaceDirectory(targetId)
    } yield (targetId, target, sourceRoot)

  private def decodeJavapFromClassFile(
      pathInfo: PathInfo,
      verbose: Boolean
  ): Future[Either[DecoderError, String]] = {
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
        .map(_ => Right(sb.toString))
    } catch {
      case NonFatal(e) =>
        scribe.error(e.toString())
        Future.successful(
          Left(Failed("Running the javap process failed."))
        )
    }
  }

  private def decodeFromSemanticDBFile(
      pathInfo: PathInfo,
      format: Format
  ): Future[Either[DecoderError, String]] =
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
      }.toEitherWith(t => Failed(t.toString()))
    }

  private def decodeFromTastyFile(
      pathInfo: PathInfo
  ): Future[Either[DecoderError, String]] =
    loadPresentationCompiler(pathInfo) match {
      case Some(pc) =>
        pc.getTasty(
          pathInfo.path.toURI,
          clientConfig.isHttpEnabled()
        ).asScala
          .map(Right(_))
      case None =>
        Future.successful(Left(Failed(("Couldn't load presentation compiler"))))
    }

  private def loadPresentationCompiler(
      pathInfo: PathInfo
  ): Option[PresentationCompiler] =
    for {
      targetId <- pathInfo.targetId
      pc <- compilers.loadCompiler(targetId)
    } yield pc

  def getTastyForURI(
      uri: URI
  ): Future[Either[String, String]] = {
    val pathInfo =
      for {
        path <- Try(AbsolutePath.fromAbsoluteUri(uri)) match {
          case Success(path) if !path.isFile => Left(s"$uri doesn't exist")
          case Success(path) if !path.isTasty =>
            Left(s"$uri doesn't point to tasty file")
          case Success(path) if path.isFile => Right(path)
          case Failure(_) => Left(s"$uri has to be absolute")
        }
        pathInfo <- findPathInfoForClassesPathFile(path).toRight(
          s"Can't find existing build target for $uri"
        )
      } yield pathInfo

    pathInfo match {
      case Left(error) => Future.successful(Left(error))
      case Right(pathInfo) =>
        decodeFromTastyFile(pathInfo).map {
          _.mapLeft {
            case Cancelled => "Request was cancelled"
            case Failed(error) => error
          }
        }
    }
  }

  def chooseClassFromFile(
      path: AbsolutePath,
      includeInnerClasses: Boolean
  ): Future[DecoderResponse] =
    findPathInfoForScalaFile(path, includeInnerClasses).map {
      case Right(PathInfo(_, resourcePath)) =>
        DecoderResponse(
          path.toURI.toString,
          resourcePath.toURI.toString,
          null
        )
      case Left(error) =>
        error.toDecoderResponse(path.toURI.toString())
    }
}
