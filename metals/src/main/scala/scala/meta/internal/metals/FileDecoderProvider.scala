package scala.meta.internal.metals

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.URI
import java.net.URLEncoder
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
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.clients.language.MetalsQuickPickItem
import scala.meta.internal.metals.clients.language.MetalsQuickPickParams
import scala.meta.internal.metap.DocumentPrinter
import scala.meta.internal.metap.Main
import scala.meta.internal.mtags.SemanticdbClasspath
import scala.meta.internal.mtags.URIEncoderDecoder
import scala.meta.internal.parsing.ClassArtifact
import scala.meta.internal.parsing.ClassFinder
import scala.meta.internal.parsing.ClassFinderGranularity
import scala.meta.internal.semanticdb.TextDocument
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
    @Nullable error: String,
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
    interactiveSemanticdbs: InteractiveSemanticdbs,
    languageClient: MetalsLanguageClient,
    clientConfig: ClientConfiguration,
    classFinder: ClassFinder,
)(implicit ec: ExecutionContext) {

  private case class PathInfo(
      targetId: BuildTargetIdentifier,
      path: AbsolutePath,
  )
  private case class BuildTargetMetadata(
      targetId: BuildTargetIdentifier,
      classDir: AbsolutePath,
      targetRoot: AbsolutePath,
      workspaceDir: AbsolutePath,
      sourceRoot: AbsolutePath,
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
    Try(URI.create(URIEncoderDecoder.encode(uriAsStr))) match {
      case Success(uri) =>
        uri.getScheme() match {
          case "jar" =>
            val jarURI = convertJarStrToURI(uriAsStr)
            if (semanticdbExtensions.exists(uriAsStr.endsWith))
              decodeMetalsFile(jarURI)
            else
              Future { decodeJar(jarURI, uriAsStr) }
          case "file" => decodeMetalsFile(uri)
          case "metalsDecode" =>
            decodedFileContents(uri.getSchemeSpecificPart())
          case _ =>
            Future.successful(
              DecoderResponse.failed(
                uri,
                s"Unexpected scheme ${uri.getScheme()}",
              )
            )
        }
      case Failure(_) =>
        Future.successful(
          DecoderResponse.failed(uriAsStr, s"$uriAsStr is an invalid URI")
        )
    }
  }

  private def convertJarStrToURI(uriAsStr: String): URI = {
    // Windows treats % literally:
    // https://learn.microsoft.com/en-us/troubleshoot/windows-client/networking/url-encoding-unc-paths-not-url-decoded
    val decoded =
      if (Properties.isWin) URIEncoderDecoder.decode(uriAsStr) else uriAsStr

    /**
     *  URI.create will decode the string, which means ZipFileSystemProvider will not work
     *  Related stack question: https://stackoverflow.com/questions/9873845/java-7-zip-file-system-provider-doesnt-seem-to-accept-spaces-in-uri
     */
    new URI("jar", decoded.stripPrefix("jar:"), null)
  }

  private def decodeJar(uri: URI, original: String): DecoderResponse = {
    Try(uri.toAbsolutePath.readText)
      .orElse(Try(original.toAbsolutePath.readText)) match {
      case Failure(exception) =>
        scribe.warn(s"Unable to decode: $original", exception)
        DecoderResponse.failed(uri, exception)
      case Success(value) => DecoderResponse.success(uri, value)
    }
  }

  private val semanticdbExtensions = Set(
    "semanticdb-compact",
    "semanticdb-detailed",
    "semanticdb-proto",
  )

  private def decodeMetalsFile(
      uri: URI
  ): Future[DecoderResponse] = {
    val supportedExtensions = Set(
      "javap",
      "javap-verbose",
      "tasty-decoded",
      "cfr",
    ) ++ semanticdbExtensions
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
      suffixToRemove: String,
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
      decode: AbsolutePath => Future[DecoderResponse],
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
      selectClassFromScalaFileAndDecode(
        path.toURI,
        path,
        ClassFinderGranularity.ClassFiles,
      )(p => decode(p.path))
    else
      Future.successful(
        DecoderResponse.failed(
          path.toURI,
          """Invalid extension. Metals can only decode ".java" or ".scala" files.""",
        )
      )
  }

  private def decodeBuildTarget(uri: URI): DecoderResponse = {
    val targetName =
      URIEncoderDecoder.decode(
        uri.toString.stripSuffix(".metals-buildtarget").split('/').last
      )
    val text =
      new BuildTargetInfo(buildTargets).buildTargetDetails(targetName)
    DecoderResponse.success(uri, text)
  }

  private def decodeSemanticDb(
      path: AbsolutePath,
      format: Format,
  ): DecoderResponse = {
    if (path.isScalaOrJava)
      interactiveSemanticdbs
        .textDocument(path)
        .documentIncludingStale
        .map(decodeFromSemanticDBTextDocument(path, _, format))
        .getOrElse(
          findSemanticDbPathInfo(path)
            .mapLeft(s => DecoderResponse.failed(path.toURI, s))
            .map(decodeFromSemanticDBFile(_, format))
            .fold(identity, identity)
        )
    else if (path.isSemanticdb) decodeFromSemanticDBFile(path, format)
    else DecoderResponse.failed(path.toURI, "Unsupported extension")
  }

  private def isScala3(path: AbsolutePath): Boolean = {
    buildTargets
      .scalaVersion(path)
      .exists(version => ScalaVersions.isScala3Version(version))
  }

  private def decodeTasty(
      path: AbsolutePath
  ): Future[DecoderResponse] = {
    if (path.isScala && isScala3(path)) {
      selectClassFromScalaFileAndDecode(
        path.toURI,
        path,
        ClassFinderGranularity.Tasty,
      )(
        decodeFromTastyFile
      )
    } else if (path.isScala) {
      Future.successful(
        DecoderResponse.failed(
          path.toURI,
          "Decoding tasty is only supported in Scala 3 for now.",
        )
      )
    } else if (path.isTasty) {
      findPathInfoForClassesPathFile(path) match {
        case Some(pathInfo) => decodeFromTastyFile(pathInfo)
        case None =>
          Future.successful(
            DecoderResponse.failed(
              path.toURI,
              "Cannot find build target for a given file",
            )
          )
      }
    } else {
      Future.successful(
        DecoderResponse.failed(path.toURI, "Invalid extension")
      )
    }
  }

  private def findPathInfoFromJavaSource(
      sourceFile: AbsolutePath,
      newExtension: String,
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
   * @param searchGranularity - identifies if we are looking for .class or .tasty files
   */
  private def selectClassFromScalaFileAndDecode[T](
      requestedURI: URI,
      path: AbsolutePath,
      searchGranularity: ClassFinderGranularity,
  )(decode: PathInfo => Future[DecoderResponse]): Future[DecoderResponse] = {
    val availableClasses = classFinder.findAllClasses(path, searchGranularity)
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
            "File doesn't contain any definitions",
          )
        )
    }
  }

  private def pickClass(
      classes: Vector[ClassArtifact]
  ): Future[Option[String]] =
    if (classes.size > 1) {
      val quickPickParams = MetalsQuickPickParams(
        classes
          .map(c =>
            MetalsQuickPickItem(
              id = c.resourcePath,
              label = c.prettyName,
              description = c.resourceMangledName,
            )
          )
          .asJava,
        placeHolder = "Pick the class you want to decode",
      )
      languageClient
        .metalsQuickPick(quickPickParams)
        .asScala
        .mapOptionInside(_.itemId)
    } else
      Future.successful(Some(classes.head.resourcePath))

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
            workspace,
          )
          .toRight(
            s"Cannot find semanticDB for ${sourceFile.toURI.toString}"
          )
      }
    } yield foundSemanticDbPath.path

  private def findJavaBuildTargetMetadata(
      targetId: BuildTargetIdentifier
  ): Option[(String, AbsolutePath)] = {
    for {
      javaTarget <- buildTargets.javaTarget(targetId)
      classDir = javaTarget.classDirectory
      targetroot = javaTarget.targetroot
    } yield (classDir, targetroot)
  }

  private def findScalaBuildTargetMetadata(
      targetId: BuildTargetIdentifier
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
          findJavaBuildTargetMetadata(targetId)
        else
          findScalaBuildTargetMetadata(targetId)
    } yield BuildTargetMetadata(
      targetId,
      classDir.toAbsolutePath,
      targetroot,
      workspaceDirectory,
      sourceRoot,
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
          logInfo = false,
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

    val buildTarget = buildTargets
      .inferBuildTarget(path)
      .orElse(
        buildTargets.allScala
          .find(buildTarget =>
            path.isInside(buildTarget.classDirectory.toAbsolutePath)
          )
          .map(_.id)
      )
      .orElse(
        buildTargets.allJava
          .find(buildTarget =>
            path.isInside(buildTarget.classDirectory.toAbsolutePath)
          )
          .map(_.id)
      )
    val classpaths = buildTarget
      .flatMap(id =>
        buildTargets
          .targetClasspath(id)
          .map(path => path.toAbsoluteClasspath.toList)
      )
      .getOrElse(Nil)
    val classesDirs = buildTarget
      .map(id =>
        buildTargets.targetClassDirectories(id).toAbsoluteClasspath.toList
      )
      .getOrElse(Nil)
    val extraClassPaths = classesDirs ::: classpaths
    val extraClassPath =
      if (extraClassPaths.nonEmpty)
        List("--extraclasspath", extraClassPaths.mkString(File.pathSeparator))
      else Nil

    // if the class file is in the classes dir then use that classes dir to allow CFR to use the other classes
    val (parent, className) = classesDirs
      .find(classesPath => path.isInside(classesPath))
      .map(classesPath => {
        val classPath = path.toRelative(classesPath)
        val className = classPath.toString
        (classesPath, className)
      })
      .getOrElse({
        val parent = path.parent
        val className = path.filename
        (parent, className)
      })

    val args = extraClassPath :::
      List(
        // elideScala - must be lowercase - hide @@ScalaSignature and serialVersionUID for aesthetic reasons
        "--elidescala",
        "true",
        // analyseAs - must be lowercase
        "--analyseas",
        "CLASS",
        s"$className",
      )

    val sbOut = new StringBuilder()
    val sbErr = new StringBuilder()
    try {
      shellRunner
        .runJava(
          cfrDependency,
          cfrMain,
          parent,
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
          propagateError = true,
        )
        .map(_ => {
          if (sbOut.isEmpty && sbErr.nonEmpty)
            DecoderResponse.failed(
              path.toURI,
              s"$cfrDependency\n$cfrMain\n$parent\n$args\n${sbErr.toString}",
            )
          else
            DecoderResponse.success(path.toURI, sbOut.toString)
        })
    } catch {
      case NonFatal(e) =>
        scribe.error(e.toString())
        Future.successful(DecoderResponse.failed(path.toURI, e))
    }
  }

  private def decodeFromSemanticDB(
      path: AbsolutePath,
      decode: Reporter => Unit,
  ): DecoderResponse =
    Try {
      val out = new ByteArrayOutputStream()
      val err = new ByteArrayOutputStream()
      val psOut = new PrintStream(out)
      val psErr = new PrintStream(err)
      try {
        val reporter =
          Reporter().withOut(psOut).withErr(psErr)
        decode(reporter)
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
      case Failure(exception) => DecoderResponse.failed(path.toURI, exception)
      case Success(value) => DecoderResponse.success(path.toURI, value)
    }

  private def decodeFromSemanticDBTextDocument(
      path: AbsolutePath,
      document: TextDocument,
      format: Format,
  ): DecoderResponse =
    decodeFromSemanticDB(
      path,
      reporter => {
        val settings = Settings().withFormat(format)
        val printer = new DocumentPrinter(settings, reporter, document)
        printer.print()
      },
    )

  private def decodeFromSemanticDBFile(
      path: AbsolutePath,
      format: Format,
  ): DecoderResponse =
    decodeFromSemanticDB(
      path,
      reporter => {
        val settings =
          Settings()
            .withPaths(List(path.toNIO))
            .withFormat(format)
        val main = new Main(settings, reporter)
        main.process()
      },
    )

  private def decodeFromTastyFile(
      pathInfo: PathInfo
  ): Future[DecoderResponse] =
    compilers.loadCompiler(pathInfo.targetId) match {
      case Some(pc) =>
        pc.getTasty(
          pathInfo.path.toURI,
          clientConfig.isHttpEnabled(),
        ).asScala
          .map(DecoderResponse.success(pathInfo.path.toURI, _))
      case None =>
        Future.successful(
          DecoderResponse.failed(
            pathInfo.path.toURI,
            "Cannot load presentation compiler",
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
      searchGranularity: ClassFinderGranularity,
  ): Future[DecoderResponse] =
    selectClassFromScalaFileAndDecode(path.toURI, path, searchGranularity) {
      pathInfo =>
        Future.successful(
          DecoderResponse.success(path.toURI, pathInfo.path.toURI.toString())
        )
    }
}

object FileDecoderProvider {
  def createBuildTargetURI(
      workspace: AbsolutePath,
      buildTargetName: String,
  ): URI =
    URI.create(
      s"metalsDecode:${URLEncoder.encode(s"file:///${workspace.filename}/${buildTargetName}.metals-buildtarget")}"
    )
}
