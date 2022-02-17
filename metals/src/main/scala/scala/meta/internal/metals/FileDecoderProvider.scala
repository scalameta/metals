package scala.meta.internal.metals

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URI
import java.nio.charset.StandardCharsets
import javax.annotation.Nullable

import scala.annotation.tailrec
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
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.clients.language.MetalsQuickPickItem
import scala.meta.internal.metals.clients.language.MetalsQuickPickParams
import scala.meta.internal.metap.Main
import scala.meta.internal.mtags.SemanticdbClasspath
import scala.meta.internal.parsing.ClassFinder
import scala.meta.internal.parsing.ClassWithPos
import scala.meta.io.AbsolutePath
import scala.meta.metap.Format
import scala.meta.metap.Settings

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import coursierapi._

/* Response which is sent to the lsp client. Because of java serialization we cannot use
 * sealed hierarchy to model union type of success and error.
 * Moreover, we cannot use Option to indicate optional values, so instead every field is nullable.
 * */
final case class DecoderResponse(
    requestedUri: String,
    @Nullable value: String,
    @Nullable error: String
)

object DecoderResponse {
  def success(uri: URI, value: String): DecoderResponse =
    DecoderResponse(uri.toString(), value, null)

  def failed(uri: String, errorMsg: String): DecoderResponse =
    DecoderResponse(uri, null, errorMsg)

  def failed(uri: URI, errorMsg: String): DecoderResponse =
    failed(uri.toString(), errorMsg)

  private def getAllMessages(e: Throwable): String = {
    @tailrec
    def getAllMessages(e: Throwable, msgs: Vector[String]): Vector[String] = {
      val cause = e.getCause
      val newMsgs = msgs :+ e.getMessage
      if (cause == e || cause == null)
        newMsgs
      else
        getAllMessages(cause, newMsgs)
    }
    getAllMessages(e, Vector.empty[String]).mkString(Properties.lineSeparator)
  }
  def failed(uri: String, e: Throwable): DecoderResponse =
    failed(uri.toString(), getAllMessages(e))

  def failed(uri: URI, e: Throwable): DecoderResponse =
    failed(uri, getAllMessages(e))

  def cancelled(uri: URI): DecoderResponse =
    DecoderResponse(uri.toString(), null, null)
}

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
      targetId: BuildTargetIdentifier,
      path: AbsolutePath
  )
  private case class BuildTargetMetadata(
      targetId: BuildTargetIdentifier,
      classDir: AbsolutePath,
      targetRoot: AbsolutePath,
      workspaceDir: AbsolutePath,
      sourceRoot: AbsolutePath
  )

  /**
   * URI format...
   * metalsDecode:fileUrl.decodeExtension
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
   * CFR:
   * metalsDecode:file:///somePath/someFile.java.cfr
   * metalsDecode:file:///somePath/someFile.scala.cfr
   * metalsDecode:file:///somePath/someFile.class.cfr
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
   *
   * build target:
   * metalsDecode:file:///workspacePath/buildTargetName.metals-buildtarget
   */
  def decodedFileContents(uriAsStr: String): Future[DecoderResponse] = {
    Try(URI.create(uriAsStr)) match {
      case Success(uri) =>
        uri.getScheme() match {
          case "jar" => Future { decodeJar(uriAsStr) }
          case "file" => decodeMetalsFile(uri)
          case "metalsDecode" =>
            decodedFileContents(uri.getSchemeSpecificPart())
          case _ =>
            Future.successful(
              DecoderResponse.failed(
                uri,
                s"Unexpected scheme ${uri.getScheme()}"
              )
            )
        }
      case Failure(_) =>
        Future.successful(
          DecoderResponse.failed(uriAsStr, s"$uriAsStr is an invalid URI")
        )
    }
  }

  private def decodeJar(uriAsStr: String): DecoderResponse = {

    /**
     *  URI.create will decode the string, which means ZipFileSystemProvider will not work
     *  Related stack question: https://stackoverflow.com/questions/9873845/java-7-zip-file-system-provider-doesnt-seem-to-accept-spaces-in-uri
     */
    val uri = new URI("jar", uriAsStr.stripPrefix("jar:"), null)
    Try {
      val path = uri.toAbsolutePath
      FileIO.slurp(path, StandardCharsets.UTF_8)
    } match {
      case Failure(exception) => DecoderResponse.failed(uri, exception)
      case Success(value) => DecoderResponse.success(uri, value)
    }
  }

  private def decodeMetalsFile(
      uri: URI
  ): Future[DecoderResponse] = {
    val supportedExtensions = Set("javap", "javap-verbose", "tasty-decoded",
      "semanticdb-compact", "semanticdb-detailed", "semanticdb-proto", "cfr")
    val additionalExtension = uri.toString().split('.').toList.last
    if (supportedExtensions(additionalExtension)) {
      val stripped = toFile(uri, s".$additionalExtension")
      stripped match {
        case Left(value) => Future.successful(value)
        case Right(path) =>
          additionalExtension match {
            case "javap" =>
              decodeJavaOrScalaOrClass(path, decodeJavapFromClassFile(false))
            case "javap-verbose" =>
              decodeJavaOrScalaOrClass(path, decodeJavapFromClassFile(true))
            case "cfr" => decodeJavaOrScalaOrClass(path, decodeCFRFromClassFile)
            case "tasty-decoded" => decodeTasty(path)
            case "semanticdb-compact" =>
              Future.successful(
                decodeSemanticDb(path, Format.Compact)
              )
            case "semanticdb-detailed" =>
              Future.successful(
                decodeSemanticDb(path, Format.Detailed)
              )
            case "semanticdb-proto" =>
              Future.successful(
                decodeSemanticDb(path, Format.Proto)
              )
          }
      }
    } else
      additionalExtension match {
        case "metals-buildtarget" =>
          Future.successful(
            decodeBuildTarget(uri)
          )
        case _ =>
          Future.successful(
            DecoderResponse.failed(uri, "Unsupported extension")
          )
      }
  }

  private def toFile(
      uri: URI,
      suffixToRemove: String
  ): Either[DecoderResponse, AbsolutePath] = {
    val strippedURI = uri.toString.stripSuffix(suffixToRemove)
    Try {
      strippedURI.toAbsolutePath
    }.filter(_.exists)
      .toOption
      .toRight(
        DecoderResponse.failed(uri, s"File $strippedURI doesn't exist")
      )
  }

  private def decodeJavaOrScalaOrClass(
      path: AbsolutePath,
      decode: AbsolutePath => Future[DecoderResponse]
  ): Future[DecoderResponse] = {
    if (path.isClassfile) decode(path)
    else if (path.isJava) {
      findPathInfoFromJavaSource(path, "class")
        .map(p => decode(p.path)) match {
        case Left(err) =>
          Future.successful(DecoderResponse.failed(path.toURI, err))
        case Right(response) => response
      }
    } else if (path.isScala)
      selectClassFromScalaFileAndDecode(path.toURI, path, true)(p =>
        decode(p.path)
      )
    else
      Future.successful(
        DecoderResponse.failed(
          path.toURI,
          """Invalid extension. Metals can only decode ".java" or ".scala" files."""
        )
      )
  }

  private def decodeBuildTarget(uri: URI): DecoderResponse = {
    val targetName =
      uri.toString.stripSuffix(".metals-buildtarget").split('/').last
    val text =
      new BuildTargetInfo(buildTargets).buildTargetDetails(targetName)
    DecoderResponse.success(uri, text)
  }

  private def decodeSemanticDb(
      path: AbsolutePath,
      format: Format
  ): DecoderResponse = {
    if (path.isScalaOrJava)
      findSemanticDbPathInfo(path)
        .mapLeft(s => DecoderResponse.failed(path.toURI, s))
        .map(decodeFromSemanticDBFile(_, format))
        .fold(identity, identity)
    else if (path.isSemanticdb) decodeFromSemanticDBFile(path, format)
    else DecoderResponse.failed(path.toURI, "Unsupported extension")
  }

  private def decodeTasty(
      path: AbsolutePath
  ): Future[DecoderResponse] = {
    if (path.isScala)
      selectClassFromScalaFileAndDecode(path.toURI, path, false)(
        decodeFromTastyFile
      )
    else if (path.isTasty) {
      findPathInfoForClassesPathFile(path) match {
        case Some(pathInfo) => decodeFromTastyFile(pathInfo)
        case None =>
          Future.successful(
            DecoderResponse.failed(
              path.toURI,
              "Cannot find build target for a given file"
            )
          )
      }
    } else
      Future.successful(DecoderResponse.failed(path.toURI, "Invalid extension"))
  }

  private def findPathInfoFromJavaSource(
      sourceFile: AbsolutePath,
      newExtension: String
  ): Either[String, PathInfo] =
    findBuildTargetMetadata(sourceFile)
      .map(metadata => {
        val oldExtension = sourceFile.extension
        val relativePath = sourceFile
          .toRelative(metadata.sourceRoot)
          .resolveSibling(_.stripSuffix(oldExtension) + newExtension)
        PathInfo(metadata.targetId, metadata.classDir.resolve(relativePath))
      })

  private def findPathInfoForClassesPathFile(
      path: AbsolutePath
  ): Option[PathInfo] = {
    val pathInfos = for {
      targetId <- buildTargets.allBuildTargetIds
      classDir <- buildTargets.targetClassDirectories(targetId)
      classPath = classDir.toAbsolutePath
      if (path.isInside(classPath))
    } yield PathInfo(targetId, path)
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
  private def selectClassFromScalaFileAndDecode[T](
      requestedURI: URI,
      path: AbsolutePath,
      includeInnerClasses: Boolean
  )(decode: PathInfo => Future[DecoderResponse]): Future[DecoderResponse] = {
    val availableClasses = classFinder.findAllClasses(path, includeInnerClasses)
    availableClasses match {
      case Some(classes) if classes.nonEmpty =>
        val resourceToDecode = pickClass(classes)
        resourceToDecode.flatMap { picked =>
          val response = for {
            resourcePath <- picked.toRight(
              DecoderResponse.cancelled(path.toURI)
            )
            buildMetadata <- findBuildTargetMetadata(path).mapLeft(
              DecoderResponse.failed(requestedURI, _)
            )
          } yield {
            val pathToResource = buildMetadata.classDir.resolve(resourcePath)
            PathInfo(buildMetadata.targetId, pathToResource)
          }
          response match {
            case Left(decoderResponse) => Future.successful(decoderResponse)
            case Right(pathInfo) => decode(pathInfo)
          }
        }
      case _ =>
        Future.successful(
          DecoderResponse.failed(
            requestedURI,
            "File doesn't contain any definitions"
          )
        )
    }
  }

  private def pickClass(classes: List[ClassWithPos]): Future[Option[String]] =
    if (classes.size > 1) {
      val quickPickParams = MetalsQuickPickParams(
        classes
          .map(c => MetalsQuickPickItem(c.path, c.friendlyName, c.description))
          .asJava,
        placeHolder = "Pick the class you want to decode"
      )
      languageClient
        .metalsQuickPick(quickPickParams)
        .asScala
        .mapOptionInside(_.itemId)
    } else
      Future.successful(Some(classes.head.path))

  private def findSemanticDbPathInfo(
      sourceFile: AbsolutePath
  ): Either[String, AbsolutePath] =
    for {
      metadata <- findBuildTargetMetadata(sourceFile)
      foundSemanticDbPath <- {
        val relativePath = SemanticdbClasspath.fromScalaOrJava(
          sourceFile.toRelative(metadata.workspaceDir.dealias)
        )
        fileSystemSemanticdbs
          .findSemanticDb(
            relativePath,
            metadata.targetRoot,
            sourceFile,
            workspace
          )
          .toRight(
            s"Cannot find semanticDB for ${sourceFile.toURI.toString}"
          )
      }
    } yield foundSemanticDbPath.path

  private def findJavaBuildTargetMetadata(
      targetId: BuildTargetIdentifier,
      sourceFile: AbsolutePath
  ): Option[(String, AbsolutePath)] = {
    for {
      javaTarget <- buildTargets.javaTarget(targetId)
      classDir = javaTarget.classDirectory
      targetroot = javaTarget.targetroot
    } yield (classDir, targetroot)
  }

  private def findScalaBuildTargetMetadata(
      targetId: BuildTargetIdentifier,
      sourceFile: AbsolutePath
  ): Option[(String, AbsolutePath)] = {
    for {
      scalaTarget <- buildTargets.scalaTarget(targetId)
      classDir = scalaTarget.classDirectory
      targetroot = scalaTarget.targetroot
    } yield (classDir, targetroot)
  }

  private def findBuildTargetMetadata(
      sourceFile: AbsolutePath
  ): Either[String, BuildTargetMetadata] = {
    val metadata = for {
      targetId <- buildTargets.inverseSources(sourceFile)
      workspaceDirectory <- buildTargets.workspaceDirectory(targetId)
      sourceRoot <- buildTargets.inverseSourceItem(sourceFile)
      (classDir, targetroot) <-
        if (sourceFile.isJava)
          findJavaBuildTargetMetadata(targetId, sourceFile)
        else
          findScalaBuildTargetMetadata(targetId, sourceFile)
    } yield BuildTargetMetadata(
      targetId,
      classDir.toAbsolutePath,
      targetroot,
      workspaceDirectory,
      sourceRoot
    )
    metadata.toRight(
      s"Cannot find build's metadata for ${sourceFile.toURI.toString()}"
    )
  }

  private def decodeJavapFromClassFile(
      verbose: Boolean
  )(path: AbsolutePath): Future[DecoderResponse] = {
    try {
      val defaultArgs = List("-private")
      val args = if (verbose) "-verbose" :: defaultArgs else defaultArgs
      val sbOut = new StringBuilder()
      val sbErr = new StringBuilder()
      shellRunner
        .run(
          "Decode using javap",
          JavaBinary(userConfig().javaHome, "javap") :: args ::: List(
            path.filename
          ),
          path.parent,
          redirectErrorOutput = false,
          Map.empty,
          s => {
            sbOut.append(s)
            sbOut.append(Properties.lineSeparator)
          },
          s => {
            sbErr.append(s)
            sbErr.append(Properties.lineSeparator)
          },
          propagateError = true,
          logInfo = false
        )
        .map(_ => {
          if (sbErr.nonEmpty)
            DecoderResponse.failed(path.toURI, sbErr.toString)
          else
            DecoderResponse.success(path.toURI, sbOut.toString)
        })
    } catch {
      case NonFatal(e) =>
        scribe.error(e.toString())
        Future.successful(DecoderResponse.failed(path.toURI, e))
    }
  }

  private def decodeCFRFromClassFile(
      path: AbsolutePath
  ): Future[DecoderResponse] = {
    val cfrDependency = Dependency.of("org.benf", "cfr", "0.151")
    val cfrMain = "org.benf.cfr.reader.Main"
    val args = List("--analyseas", "CLASS", s"""${path.toNIO.toString}""")
    val sbOut = new StringBuilder()
    val sbErr = new StringBuilder()

    try {
      shellRunner
        .runJava(
          cfrDependency,
          cfrMain,
          path.parent,
          args,
          redirectErrorOutput = false,
          s => {
            sbOut.append(s)
            sbOut.append(Properties.lineSeparator)
          },
          s => {
            sbErr.append(s)
            sbErr.append(Properties.lineSeparator)
          },
          propagateError = true
        )
        .map(_ => {
          if (sbErr.nonEmpty)
            DecoderResponse.failed(path.toURI, sbErr.toString)
          else
            DecoderResponse.success(path.toURI, sbOut.toString)
        })
    } catch {
      case NonFatal(e) =>
        scribe.error(e.toString())
        Future.successful(DecoderResponse.failed(path.toURI, e))
    }
  }

  private def decodeFromSemanticDBFile(
      path: AbsolutePath,
      format: Format
  ): DecoderResponse =
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
            .withPaths(List(path.toNIO))
            .withFormat(format)
        val main = new Main(settings, reporter)
        main.process()
        val output = new String(out.toByteArray)
        val error = new String(err.toByteArray)
        if (error.isEmpty)
          output
        else
          error
      } finally {
        psOut.close()
        psErr.close()
      }
    } match {
      case Failure(exception) =>
        DecoderResponse.failed(path.toString(), exception)
      case Success(value) => DecoderResponse.success(path.toURI, value)
    }

  private def decodeFromTastyFile(
      pathInfo: PathInfo
  ): Future[DecoderResponse] =
    compilers.loadCompiler(pathInfo.targetId) match {
      case Some(pc) =>
        pc.getTasty(
          pathInfo.path.toURI,
          clientConfig.isHttpEnabled()
        ).asScala
          .map(DecoderResponse.success(pathInfo.path.toURI, _))
      case None =>
        Future.successful(
          DecoderResponse.failed(
            pathInfo.path.toURI,
            "Cannot load presentation compiler"
          )
        )
    }

  def getTastyForURI(
      uri: URI
  ): Future[Either[String, String]] = {
    decodeTasty(AbsolutePath.fromAbsoluteUri(uri)).map(response =>
      if (response.value != null) Right(response.value)
      else Left(response.error)
    )
  }

  def chooseClassFromFile(
      path: AbsolutePath,
      includeInnerClasses: Boolean
  ): Future[DecoderResponse] =
    selectClassFromScalaFileAndDecode(path.toURI, path, includeInnerClasses) {
      pathInfo =>
        Future.successful(
          DecoderResponse.success(path.toURI, pathInfo.path.toURI.toString())
        )
    }
}
