package scala.meta.internal.metals

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.atomic.AtomicReference

import scala.util.Using
import scala.util.control.NonFatal

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.URIEncoderDecoder
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.TextDocumentIdentifier

/**
 * Bidirectional mapper between virtual `metalsfs://` URIs exposed to
 * the LSP client and local `jar:file://` URIs used internally by Metals.
 *
 * `try*` variants return `None` when the receiving mapper does not own
 * the archive in question, so an aggregator can fall through to another
 * folder's mapper.
 */
trait URIMapper {

  def convertToLocal(uri: String): String
  def convertToMetalsFS(uri: String): String

  def tryConvertToLocal(uri: String): Option[String]
  def tryConvertToMetalsFS(uri: String): Option[String]

  def getJDKs: Iterator[String]
  def getWorkspaceJars: Iterator[String]
  def getSourceJars: Iterator[String]

  def tryGetJDKFileSystem(name: String): Option[FileSystemInfo]
  def tryGetWorkspaceJarFileSystem(name: String): Option[FileSystemInfo]
  def tryGetSourceJarFileSystem(name: String): Option[FileSystemInfo]

  final def getJDKFileSystem(name: String): FileSystemInfo =
    tryGetJDKFileSystem(name).getOrElse(
      throw new NoSuchElementException(s"JDK not found: $name")
    )

  final def getWorkspaceJarFileSystem(name: String): FileSystemInfo =
    tryGetWorkspaceJarFileSystem(name).getOrElse(
      throw new NoSuchElementException(s"Workspace jar not found: $name")
    )

  final def getSourceJarFileSystem(name: String): FileSystemInfo =
    tryGetSourceJarFileSystem(name).getOrElse(
      throw new NoSuchElementException(s"Source jar not found: $name")
    )

  final def convertToMetalsFS(
      symbolInformation: SymbolInformation
  ): SymbolInformation = {
    val symbolInfo = new SymbolInformation(
      symbolInformation.getName,
      symbolInformation.getKind,
      convertToMetalsFS(symbolInformation.getLocation),
      symbolInformation.getContainerName,
    )
    symbolInfo.setTags(symbolInformation.getTags)
    symbolInfo
  }

  final def convertToMetalsFS(location: Location): Location =
    new Location(convertToMetalsFS(location.getUri), location.getRange)

  final def convertToLocal(
      params: TextDocumentIdentifier
  ): TextDocumentIdentifier =
    new TextDocumentIdentifier(convertToLocal(params.getUri))

  final def convertToLocal(params: HoverExtParams): HoverExtParams =
    params.copy(textDocument = convertToLocal(params.textDocument))

  final def convertToLocal(params: CodeActionParams): CodeActionParams =
    new CodeActionParams(
      convertToLocal(params.getTextDocument()),
      params.getRange(),
      params.getContext(),
    )
}

/**
 * Per-folder [[URIMapper]] backed by that folder's [[BuildTargets]] and
 * the shared [[JarFileSystemCache]].
 */
final class FolderURIMapper(
    buildTargets: BuildTargets,
    userJavaHome: () => Option[String],
    jarFileSystemCache: JarFileSystemCache,
) extends URIMapper {

  private val emptyIndex: Map[String, AbsolutePath] = Map.empty
  private val jdkIndex = new AtomicReference(emptyIndex)
  private val workspaceJarIndex = new AtomicReference(emptyIndex)
  private val sourceJarIndex = new AtomicReference(emptyIndex)

  /**
   * Derives a display name for a JDK source archive (`src.zip`) by reading
   * `JAVA_VERSION`/`IMPLEMENTOR` from the `release` file in `JAVA_HOME`.
   * Falls back to the JDK home directory name when `release` is missing
   * (e.g. JDKs older than Java 9).
   */
  private def getDecentJDKName(srcZipPath: Path): String = {
    val jdkHome = Option(srcZipPath.getParent).map { parent =>
      if (parent.filename == "lib") parent.getParent else parent
    }
    jdkHome
      .flatMap(readReleaseName)
      .orElse(jdkHome.map(_.filename))
      .getOrElse("JDK")
  }

  private def readReleaseName(jdkHome: Path): Option[String] = {
    val release = jdkHome.resolve("release")
    if (!Files.exists(release)) None
    else {
      val props = new Properties()
      val loaded =
        try {
          Using.resource(Files.newBufferedReader(release))(props.load)
          true
        } catch {
          case NonFatal(e) =>
            scribe.warn(s"Failed to read JDK release file at $release", e)
            false
        }
      if (!loaded) None
      else {
        val version = Option(props.getProperty("JAVA_VERSION")).map(unquote)
        val implementor = Option(props.getProperty("IMPLEMENTOR")).map(unquote)
        (implementor, version) match {
          case (Some(impl), Some(ver)) => Some(s"$impl $ver")
          case (_, Some(ver)) => Some(s"JDK $ver")
          case _ => None
        }
      }
    }
  }

  private def unquote(s: String): String =
    s.stripPrefix("\"").stripSuffix("\"")

  private def jdkSources: Option[AbsolutePath] =
    JdkSources(userJavaHome()).toOption

  /** Call after indexing the build so listings reflect current state. */
  def rebuildIndexes(): Unit = synchronized {
    val newJdk =
      jdkSources.iterator.map(j => getDecentJDKName(j.toNIO) -> j).toMap
    val newWs = buildTargets.allWorkspaceJars.map(j => j.filename -> j).toMap
    val newSrc = buildTargets.allSourceJars.map(j => j.filename -> j).toMap

    val newKnownPaths =
      newJdk.values.toSet ++ newWs.values.toSet ++ newSrc.values.toSet
    jarFileSystemCache.closeObsolete(newKnownPaths)

    jdkIndex.set(newJdk)
    workspaceJarIndex.set(newWs)
    sourceJarIndex.set(newSrc)
  }

  def getJDKs: Iterator[String] = jdkIndex.get().keys.iterator
  def getWorkspaceJars: Iterator[String] =
    workspaceJarIndex.get().keys.iterator
  def getSourceJars: Iterator[String] = sourceJarIndex.get().keys.iterator

  def tryGetJDKFileSystem(name: String): Option[FileSystemInfo] =
    jdkIndex.get().get(name).map(jarFileSystemCache.open)
  def tryGetWorkspaceJarFileSystem(name: String): Option[FileSystemInfo] =
    workspaceJarIndex.get().get(name).map(jarFileSystemCache.open)
  def tryGetSourceJarFileSystem(name: String): Option[FileSystemInfo] =
    sourceJarIndex.get().get(name).map(jarFileSystemCache.open)

  private def classifyLocalUri(decodedJarPath: String): Option[String] = {
    val pathOpt =
      try Some(AbsolutePath(java.nio.file.Paths.get(new URI(decodedJarPath))))
      catch {
        case NonFatal(e) =>
          scribe.warn(
            s"Failed to parse $decodedJarPath while classifying local URI",
            e,
          )
          None
      }

    pathOpt.flatMap { path =>
      val absPathUri = path.toURI.toString.stripSuffix("/")
      val matchesJdk = jdkSources.exists { jdk =>
        absPathUri.isUriEqual(jdk.toURI.toString.stripSuffix("/"))
      }
      if (matchesJdk)
        jdkSources
          .map(jdk => s"${URIMapper.jdkURI}/${getDecentJDKName(jdk.toNIO)}")
      else if (
        buildTargets.allWorkspaceJars
          .exists(j => absPathUri.isUriEqual(j.toURI.toString.stripSuffix("/")))
      )
        Some(s"${URIMapper.workspaceJarURI}/${path.filename}")
      else if (
        buildTargets.allSourceJars
          .exists(j => absPathUri.isUriEqual(j.toURI.toString.stripSuffix("/")))
      )
        Some(s"${URIMapper.sourceJarURI}/${path.filename}")
      else
        None
    }
  }

  /**
   * `metalsfs:` URIs are returned as-is — `new URI("metalsfs", "", path, null)`
   * would otherwise produce triple slashes.
   */
  def encodeUri(uri: String): String = uri match {
    case s if s.startsWith("file://") =>
      new URI("file", "", s.stripPrefix("file://"), null).toString
    case s if s.startsWith("jar:") =>
      new URI("jar", s.stripPrefix("jar:"), null).toString
    case s if s.startsWith("metalsfs:") =>
      s
    case _ =>
      throw new IllegalArgumentException(s"Unsupported URI scheme: $uri")
  }

  def convertToLocal(uri: String): String =
    tryConvertToLocal(uri).getOrElse(uri)

  def convertToMetalsFS(uri: String): String =
    tryConvertToMetalsFS(uri).getOrElse(encodeUri(uri))

  def tryConvertToLocal(uri: String): Option[String] = {
    if (!uri.startsWith(URIMapper.parentURI)) None
    else {
      val decoded = URIEncoderDecoder.decode(uri)
      val resolved = decoded match {
        case jdk if jdk.startsWith(URIMapper.jdkURI) =>
          val (name, remaining) = URIMapper.getURIParts(jdk, URIMapper.jdkURI)
          tryGetJDKFileSystem(name).map(fs => (fs, remaining))
        case ws if ws.startsWith(URIMapper.workspaceJarURI) =>
          val (name, remaining) =
            URIMapper.getURIParts(ws, URIMapper.workspaceJarURI)
          tryGetWorkspaceJarFileSystem(name).map(fs => (fs, remaining))
        case src if src.startsWith(URIMapper.sourceJarURI) =>
          val (name, remaining) =
            URIMapper.getURIParts(src, URIMapper.sourceJarURI)
          tryGetSourceJarFileSystem(name).map(fs => (fs, remaining))
        case _ => None
      }
      resolved.map { case (fs, fsPath) =>
        fsPath.fold(fs.fileUri)(p => fs.fs.getPath(p).toUri.toString)
      }
    }
  }

  def tryConvertToMetalsFS(uri: String): Option[String] = {
    val decodedURI = URIEncoderDecoder.decode(uri)
    val metalsfsUri =
      if (uri.startsWith("jar:")) {
        val path = decodedURI.stripPrefix("jar:")
        val splitter = path.indexOf('!')
        if (splitter == -1) classifyLocalUri(path)
        else {
          val remainder = path.substring(splitter + 1)
          val localJarPath = path.substring(0, splitter)
          classifyLocalUri(localJarPath).map(prefix => s"$prefix$remainder")
        }
      } else classifyLocalUri(decodedURI)
    metalsfsUri.map(encodeUri)
  }
}

/**
 * Aggregating [[URIMapper]] that delegates to per-folder mappers,
 * returning the first non-empty match. Used by the workspace-level
 * service so requests from any folder are handled correctly.
 */
final class WorkspaceURIMapper(folders: () => Iterable[URIMapper])
    extends URIMapper {

  private def tryAll[A](f: URIMapper => Option[A]): Option[A] =
    folders().iterator.flatMap(m => f(m).iterator).nextOption()

  def convertToLocal(uri: String): String =
    tryConvertToLocal(uri).getOrElse(uri)
  def convertToMetalsFS(uri: String): String =
    tryConvertToMetalsFS(uri).getOrElse(uri)

  def tryConvertToLocal(uri: String): Option[String] =
    tryAll(_.tryConvertToLocal(uri))
  def tryConvertToMetalsFS(uri: String): Option[String] =
    tryAll(_.tryConvertToMetalsFS(uri))

  def getJDKs: Iterator[String] = folders().iterator.flatMap(_.getJDKs).distinct
  def getWorkspaceJars: Iterator[String] =
    folders().iterator.flatMap(_.getWorkspaceJars).distinct
  def getSourceJars: Iterator[String] =
    folders().iterator.flatMap(_.getSourceJars).distinct

  def tryGetJDKFileSystem(name: String): Option[FileSystemInfo] =
    tryAll(_.tryGetJDKFileSystem(name))
  def tryGetWorkspaceJarFileSystem(name: String): Option[FileSystemInfo] =
    tryAll(_.tryGetWorkspaceJarFileSystem(name))
  def tryGetSourceJarFileSystem(name: String): Option[FileSystemInfo] =
    tryAll(_.tryGetSourceJarFileSystem(name))
}

/** Single-slash form because VS Code normalises `metalsfs:///` to `metalsfs:/`. */
object URIMapper {
  val rootDir: String = "metalsLibraries"
  val parentURI: String = s"metalsfs:/$rootDir"
  val jdkDir: String = "jdk"
  val workspaceJarDir: String = "jar"
  val sourceJarDir: String = "source"
  val jdkURI: String = s"${parentURI}/$jdkDir"
  val workspaceJarURI: String = s"${parentURI}/$workspaceJarDir"
  val sourceJarURI: String = s"${parentURI}/$sourceJarDir"

  /**
   * Splits a `metalsfs://` URI into the archive name and an optional
   * inner path relative to that archive.
   *
   * {{{
   * getURIParts("metalsfs:/X/Y",      "metalsfs:/X") // ("Y", None)
   * getURIParts("metalsfs:/X/Y/a/B",  "metalsfs:/X") // ("Y", Some("a/B"))
   * }}}
   */
  def getURIParts(
      uri: String,
      prefix: String,
  ): (String, Option[String]) = {
    val basePart = uri.stripPrefix(s"${prefix}/")
    val separatorIdx = basePart.indexOf('/')
    if (separatorIdx == -1)
      (basePart, None)
    else {
      val name = basePart.substring(0, separatorIdx)
      val remaining = basePart.substring(separatorIdx + 1)
      (name, Some(remaining))
    }
  }
}
