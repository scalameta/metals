package scala.meta.internal.metals

import java.net.URI
import java.nio.file.FileSystem
import java.nio.file.FileSystemNotFoundException
import java.nio.file.FileSystems
import java.nio.file.Path

import scala.annotation.tailrec
import scala.collection.concurrent.TrieMap
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

case class URIMapper() {

  private lazy val isWindows: Boolean = Properties.isWin
  private val metalsFSToLocalJDKs = TrieMap.empty[String, FileSystemInfo]
  private val metalsFSToLocalWorkspaceJars =
    TrieMap.empty[String, FileSystemInfo]
  private val metalsFSToLocalSourceJars = TrieMap.empty[String, FileSystemInfo]
  // full local URI path -> full metals URI path
  private val localURIToMetalsURI = TrieMap.empty[String, String]

  // in Windows the uri can be sent through in different case so lowercase all.
  // e.g. file:///c:/coursier/somejar can be file:///C:/coursier/somejar in a stacktrace
  private def changeCase(uri: String): String =
    if (isWindows)
      uri.toLowerCase()
    else
      uri

  @tailrec
  private def getDecentJDKName(path: Path): String =
    if (
      path.getParent == null || !List("lib", "src.zip").contains(path.filename)
    )
      path.filename
    else
      getDecentJDKName(path.getParent)

  def addJDK(jdk: AbsolutePath): Unit = {
    val name = getDecentJDKName(jdk.toNIO)
    addJDK(name, jdk)
  }

  private def addJDK(name: String, sourcePath: AbsolutePath): Unit = {
    val metalsURI = s"${URIMapper.jdkURI}/$name"
    val localURI = sourcePath.toURI.toString
    // AbsolutePath#toURI will partially encode the URI so decode it
    val metalsEncodedURI = URIEncoderDecoder.decode(localURI)
    localURIToMetalsURI.put(changeCase(metalsEncodedURI), metalsURI)
    metalsFSToLocalJDKs.put(name, getFileSystem(sourcePath))
  }

  def addWorkspaceJar(jar: AbsolutePath): Unit = {
    val metalsURI = s"${URIMapper.workspaceJarURI}/${jar.filename}"
    val localURI = jar.toURI.toString
    // AbsolutePath#toURI will partially encode the URI so decode it
    val metalsEncodedURI = URIEncoderDecoder.decode(localURI)
    localURIToMetalsURI.put(changeCase(metalsEncodedURI), metalsURI)
    metalsFSToLocalWorkspaceJars.put(jar.filename, getFileSystem(jar))
  }

  def addSourceJar(jar: AbsolutePath): Unit = {
    val metalsURI = s"${URIMapper.sourceJarURI}/${jar.filename}"
    val localURI = jar.toURI.toString
    // AbsolutePath#toURI will partially encode the URI so decode it
    val metalsEncodedURI = URIEncoderDecoder.decode(localURI)
    localURIToMetalsURI.put(changeCase(metalsEncodedURI), metalsURI)
    metalsFSToLocalSourceJars.put(jar.filename, getFileSystem(jar))
  }

  def reset(): Unit = {
    localURIToMetalsURI.clear()
    metalsFSToLocalJDKs.clear()
    metalsFSToLocalWorkspaceJars.clear()
    metalsFSToLocalSourceJars.clear()
  }

  private def getFileSystem(localPath: AbsolutePath): FileSystemInfo = {
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

  def getJDKs: Iterator[String] =
    metalsFSToLocalJDKs.keySet.toIterator

  def getSourceJars: Iterator[String] =
    metalsFSToLocalSourceJars.keySet.toIterator

  def getWorkspaceJars: Iterator[String] =
    metalsFSToLocalWorkspaceJars.keySet.toIterator

  def getJDKFileSystem(uri: String): FileSystemInfo =
    metalsFSToLocalJDKs(uri)

  def getWorkspaceJarFileSystem(uri: String): FileSystemInfo =
    metalsFSToLocalWorkspaceJars(uri)

  def getSourceJarFileSystem(uri: String): FileSystemInfo =
    metalsFSToLocalSourceJars(uri)

  private def getMetalsURIFromLocalURI(uri: String): String = {
    if (localURIToMetalsURI.contains(changeCase(uri)))
      localURIToMetalsURI(changeCase(uri))
    else
      uri
  }

  def encodeUri(uri: String): String = {
    if (uri.startsWith("file://")) {
      val path = uri.stripPrefix("file://")
      new URI("file", "", path, null).toString
    } else if (uri.startsWith("jar:")) {
      val ssp = uri.stripPrefix("jar:")
      new URI("jar", ssp, null).toString
    } else if (uri.startsWith("metalsfs:")) {
      // shouldn't need encoding.  Definitely don't encode using URI("metalsfs", "", path, null) as this will create triple slashes: metalsfs:///somejar
      uri
    } else
      throw new IllegalStateException(s"Why here? $uri")
  }

  // transform the metalsfs URI into a local URI
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
      // no path - return the original jar uri e.g. file:///somejar.jar
      if (fsPath.isEmpty)
        // uri already encoded in getFileSystem method
        fs.fileUri
      else
        // path exists - return the jar path uri e.g. jar:file:///somejar.jar!/path
        // the Path#toUri call will encode the URI so no need to encode it manually
        fs.fs.getPath(fsPath.get).toUri.toString
    } else uri
  }

  // transform the local URI into a metalsfs URI
  def convertToMetalsFS(uri: String): String = {
    val decodedURI = URIEncoderDecoder.decode(uri)
    val metalsfsUri = if (uri.startsWith("jar:")) {
      val path = decodedURI.stripPrefix("jar:")
      val splitter = path.indexOf('!')
      if (splitter == -1) getMetalsURIFromLocalURI(path)
      else {
        val remainder = path.substring(splitter + 1)
        val localJarPath = path.substring(0, splitter)
        val metalsJarPath = getMetalsURIFromLocalURI(localJarPath)
        s"${metalsJarPath}${remainder}"
      }
    } else getMetalsURIFromLocalURI(decodedURI)
    encodeUri(metalsfsUri)
  }

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

  def convertToLocal(location: Location): Location =
    new Location(convertToLocal(location.getUri), location.getRange)

  def convertToMetalsFS(location: Location): Location =
    new Location(convertToMetalsFS(location.getUri), location.getRange)

  def convertToLocal(
      params: ReferenceParams
  ): ReferenceParams =
    new ReferenceParams(
      convertToLocal(params.getTextDocument),
      params.getPosition,
      params.getContext,
    )

  def convertToLocal(
      params: TextDocumentPositionParams
  ): TextDocumentPositionParams =
    new TextDocumentPositionParams(
      convertToLocal(params.getTextDocument),
      params.getPosition,
    )

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
case class FileSystemInfo(fs: FileSystem, fileUri: String)

object URIMapper {
  // various metals filesystem setup - change these to what we like
  val rootDir: String = "metalsLibraries"
  // vscode registers "metalsfs:///" as "metalsfs:/" so just stick to the single slash
  val parentURI: String = s"metalsfs:/$rootDir"
  val jdkDir: String = "jdk"
  val workspaceJarDir: String = "jar"
  val sourceJarDir: String = "source"
  val jdkURI: String = s"${parentURI}/$jdkDir"
  val workspaceJarURI: String = s"${parentURI}/$workspaceJarDir"
  val sourceJarURI: String = s"${parentURI}/$sourceJarDir"

  // transform the metalsfs URI into a local URI
  // ("metalsfs:/XXX/YYY", "metalsfs:/XXX") => ("YYY", None)
  // ("metalsfs:/XXX/YYY/somePackage/someClass", "metalsfs:/XXX") => ("YYY", Some("somePackage/someClass"))
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
