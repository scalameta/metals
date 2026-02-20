package scala.meta.internal.metals

import java.net.URI
import java.nio.file.FileSystem
import java.nio.file.FileSystemNotFoundException
import java.nio.file.FileSystems
import java.nio.file.Path

import scala.annotation.tailrec
import scala.util.Properties

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.URIEncoderDecoder
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentPositionParams

/**
 * Bidirectional mapper between virtual `metalsfs://` URIs exposed to
 * the LSP client and local `jar:file://` URIs used internally by Metals.
 *
 * Maintains lazy NIO file system handles for JAR archives and provides
 * overloaded conversions for common LSP4J parameter types.
 */
final case class URIMapper(
    buildTargets: BuildTargets,
    userJavaHome: () => Option[String],
) {

  private lazy val isWindows: Boolean = Properties.isWin

  private def changeCase(uri: String): String =
    if (isWindows) uri.toLowerCase() else uri

  /**
   * Walks up the path to find a meaningful JDK directory name,
   * skipping intermediate segments like `lib` or `src.zip`.
   */
  @tailrec
  private def getDecentJDKName(path: Path): String =
    if (
      path.getParent == null || !List("lib", "src.zip").contains(path.filename)
    )
      path.filename
    else
      getDecentJDKName(path.getParent)

  private def jdkSources: Option[AbsolutePath] =
    JdkSources(userJavaHome()).toOption

  /** Returns the display names of all available JDK source archives. */
  def getJDKs: Iterator[String] =
    jdkSources.iterator.map(jdk => getDecentJDKName(jdk.toNIO))

  /** Returns the filenames of all workspace dependency JARs. */
  def getWorkspaceJars: Iterator[String] =
    buildTargets.allWorkspaceJars.map(_.filename)

  /** Returns the filenames of all source JARs. */
  def getSourceJars: Iterator[String] =
    buildTargets.allSourceJars.map(_.filename)

  /**
   * Returns an existing NIO [[FileSystem]] for the given archive,
   * creating one if it does not yet exist.
   */
  private def getOrCreateFileSystem(localPath: AbsolutePath): FileSystemInfo = {
    val fileUri = localPath.toNIO.toUri.toString.stripSuffix("/")
    val localUri = s"jar:$fileUri"
    val zipURI = URI.create(localUri)
    val fs =
      try {
        FileSystems.getFileSystem(zipURI)
      } catch {
        case _: FileSystemNotFoundException =>
          FileSystems.newFileSystem(zipURI, new java.util.HashMap[String, Any])
      }
    FileSystemInfo(fs, fileUri)
  }

  private def findJdkByName(name: String): Option[AbsolutePath] =
    jdkSources.find(jdk => getDecentJDKName(jdk.toNIO) == name)

  private def findWorkspaceJarByName(name: String): Option[AbsolutePath] =
    buildTargets.allWorkspaceJars.find(_.filename == name)

  private def findSourceJarByName(name: String): Option[AbsolutePath] =
    buildTargets.allSourceJars.find(_.filename == name)

  /** Opens the NIO file system for a JDK source archive identified by name. */
  def getJDKFileSystem(name: String): FileSystemInfo =
    findJdkByName(name)
      .map(getOrCreateFileSystem)
      .getOrElse(throw new NoSuchElementException(s"JDK not found: $name"))

  /** Opens the NIO file system for a workspace JAR identified by filename. */
  def getWorkspaceJarFileSystem(name: String): FileSystemInfo =
    findWorkspaceJarByName(name)
      .map(getOrCreateFileSystem)
      .getOrElse(
        throw new NoSuchElementException(s"Workspace jar not found: $name")
      )

  /** Opens the NIO file system for a source JAR identified by filename. */
  def getSourceJarFileSystem(name: String): FileSystemInfo =
    findSourceJarByName(name)
      .map(getOrCreateFileSystem)
      .getOrElse(
        throw new NoSuchElementException(s"Source jar not found: $name")
      )

  /**
   * Determines the virtual category (jdk, jar, or source) for a local
   * `file://` JAR path by matching it against known build dependencies.
   *
   * @param decodedJarPath a decoded `file:///path/to/some.jar` string
   * @return the corresponding `metalsfs:/metalsLibraries/<category>/<name>`
   *         prefix, or `None` if the path is not a known dependency
   */
  private def classifyLocalUri(
      decodedJarPath: String
  ): Option[String] = {
    val path =
      try Some(AbsolutePath(java.nio.file.Paths.get(new URI(decodedJarPath))))
      catch { case _: Exception => None }

    path.flatMap { absPath =>
      val filename = absPath.filename
      jdkSources match {
        case Some(jdk)
            if changeCase(
              absPath.toURI.toString.stripSuffix("/")
            ) == changeCase(jdk.toURI.toString.stripSuffix("/")) =>
          val name = getDecentJDKName(jdk.toNIO)
          Some(s"${URIMapper.jdkURI}/$name")
        case _ =>
          if (
            buildTargets.allWorkspaceJars.exists(j =>
              changeCase(j.toURI.toString.stripSuffix("/")) == changeCase(
                absPath.toURI.toString.stripSuffix("/")
              )
            )
          )
            Some(s"${URIMapper.workspaceJarURI}/$filename")
          else if (
            buildTargets.allSourceJars.exists(j =>
              changeCase(j.toURI.toString.stripSuffix("/")) == changeCase(
                absPath.toURI.toString.stripSuffix("/")
              )
            )
          )
            Some(s"${URIMapper.sourceJarURI}/$filename")
          else
            None
      }
    }
  }

  /**
   * Percent-encodes the given URI according to its scheme.
   *
   * `metalsfs:` URIs are returned as-is because constructing them via
   * `new URI("metalsfs", "", path, null)` would produce triple slashes.
   */
  def encodeUri(uri: String): String = {
    if (uri.startsWith("file://")) {
      val path = uri.stripPrefix("file://")
      new URI("file", "", path, null).toString
    } else if (uri.startsWith("jar:")) {
      val ssp = uri.stripPrefix("jar:")
      new URI("jar", ssp, null).toString
    } else if (uri.startsWith("metalsfs:")) {
      uri
    } else
      throw new IllegalStateException(s"Why here? $uri")
  }

  /**
   * Converts a virtual `metalsfs://` URI into a local `jar:file://` URI.
   *
   * If the URI points to an archive root (no inner path), the plain
   * `file://` URI of the archive is returned. Otherwise the full
   * `jar:file:///archive.jar!/inner/path` form is produced.
   * The returned URI is already percent-encoded by `Path#toUri`.
   */
  def convertToLocal(uri: String): String = {
    if (uri.startsWith(URIMapper.parentURI)) {
      val (fs, fsPath) = URIEncoderDecoder.decode(uri) match {
        case jdk if jdk.startsWith(URIMapper.jdkURI) =>
          val (name, remaining) = URIMapper.getURIParts(jdk, URIMapper.jdkURI)
          val fs = getJDKFileSystem(name)
          (fs, remaining)
        case workspaceJar
            if workspaceJar.startsWith(URIMapper.workspaceJarURI) =>
          val (name, remaining) =
            URIMapper.getURIParts(workspaceJar, URIMapper.workspaceJarURI)
          val fs = getWorkspaceJarFileSystem(name)
          (fs, remaining)
        case sourceJar if sourceJar.startsWith(URIMapper.sourceJarURI) =>
          val (name, remaining) =
            URIMapper.getURIParts(sourceJar, URIMapper.sourceJarURI)
          val fs = getSourceJarFileSystem(name)
          (fs, remaining)
      }
      if (fsPath.isEmpty) fs.fileUri
      else fs.fs.getPath(fsPath.get).toUri.toString
    } else uri
  }

  /**
   * Converts a local `jar:file://` URI into a virtual `metalsfs://` URI
   * by classifying the archive and rewriting the path.
   */
  def convertToMetalsFS(uri: String): String = {
    val decodedURI = URIEncoderDecoder.decode(uri)
    val metalsfsUri = if (uri.startsWith("jar:")) {
      val path = decodedURI.stripPrefix("jar:")
      val splitter = path.indexOf('!')
      if (splitter == -1) {
        classifyLocalUri(path).getOrElse(path)
      } else {
        val remainder = path.substring(splitter + 1)
        val localJarPath = path.substring(0, splitter)
        val metalsJarPath =
          classifyLocalUri(localJarPath).getOrElse(localJarPath)
        s"${metalsJarPath}${remainder}"
      }
    } else {
      classifyLocalUri(decodedURI).getOrElse(decodedURI)
    }
    encodeUri(metalsfsUri)
  }

  /** Rewrites the location URI inside a [[SymbolInformation]] to `metalsfs://`. */
  def convertToMetalsFS(
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

  def convertToMetalsFS(location: Location): Location =
    new Location(convertToMetalsFS(location.getUri), location.getRange)

  def convertToLocal(
      params: TextDocumentIdentifier
  ): TextDocumentIdentifier =
    new TextDocumentIdentifier(convertToLocal(params.getUri))

  def convertToLocal(
      params: HoverExtParams
  ): HoverExtParams =
    params.copy(textDocument = convertToLocal(params.textDocument))

  def convertToLocal(
      params: CodeActionParams
  ): CodeActionParams =
    new CodeActionParams(
      convertToLocal(params.getTextDocument()),
      params.getRange(),
      params.getContext(),
    )
}

/** Handle to an open NIO [[FileSystem]] together with its source `file://` URI. */
final case class FileSystemInfo(fs: FileSystem, fileUri: String)

/**
 * Constants and utilities for the `metalsfs://` virtual file system URI scheme.
 *
 * VS Code normalises `metalsfs:///` to `metalsfs:/`, so all paths
 * use the single-slash form.
 */
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
