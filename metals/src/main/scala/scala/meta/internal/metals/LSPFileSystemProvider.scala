package scala.meta.internal.metals

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.control.NonFatal

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.clients.language.MetalsLanguageClient

final case class FSReadDirectoryResponse(
    name: String,
    isFile: Boolean,
)
final case class FSReadDirectoriesResponse(
    name: String,
    directories: Array[FSReadDirectoryResponse],
    error: String,
)

final case class FSReadFileResponse(
    name: String,
    value: String,
    error: String,
)

final case class FSStatResponse(
    name: String,
    isFile: Boolean,
    error: String,
)

final class LSPFileSystemProvider(
    languageClient: MetalsLanguageClient,
    uriMapper: URIMapper,
    fileDecoderProvider: FileDecoderProvider,
    clientConfig: ClientConfiguration,
)(implicit ec: ExecutionContext) {

  // cache the last few decompiled class files as they're repeatedly accessed by VSCode once opened or referenced
  private val cache = LRUCache[Path, String](10)

  def sendLibraryFileSystemReady(): Unit = {
    if (clientConfig.isLibraryFileSystemSupported()) {
      val params =
        ClientCommands.LibraryFileSystemReady.toExecuteCommandParams()
      languageClient.metalsExecuteClientCommand(params)
    }
  }

  private def getLocalPathDirectory(
      uri: String,
      prefix: String,
      mappingFunction: String => FileSystemInfo,
  ): Array[FSReadDirectoryResponse] = {
    val (name, remaining) = URIMapper.getURIParts(uri, prefix)
    val fs = mappingFunction(name).fs
    // assume all jar paths only have `/` as root
    val path = fs.getPath(remaining.getOrElse("/"))
    Files
      .list(path)
      .collect(Collectors.toList())
      .asScala
      .map(path =>
        FSReadDirectoryResponse(
          path.getFileName.toString,
          Files.isRegularFile(path),
        )
      )
      .toArray
  }

  private def getLocalSystemStat(
      uri: String,
      prefix: String,
      mappingFunction: String => FileSystemInfo,
  ): FSStatResponse = {
    val (name, remaining) = URIMapper.getURIParts(uri, prefix)
    try {
      val fs = mappingFunction(name).fs
      // assume all jar paths only have `/` as root
      val path = fs.getPath(remaining.getOrElse("/"))
      FSStatResponse(uri, Files.isRegularFile(path), null)
    } catch {
      case NonFatal(e) =>
        scribe.error(s"getLocalSystemStat $uri $name $remaining", e)
        throw e
    }
  }

  private def getLocalFileContents(
      uri: String,
      prefix: String,
      mappingFunction: String => FileSystemInfo,
  ): FSReadFileResponse = {
    val (name, remaining) = URIMapper.getURIParts(uri, prefix)
    val fs = mappingFunction(name).fs
    val path = fs.getPath(remaining.getOrElse("/"))
    val contents =
      if (path.isClassfile) {
        val cacheResult = cache.get(path)
        cacheResult.getOrElse({
          val response =
            fileDecoderProvider.decodeCFRAndWait(path.toUri.toAbsolutePath)
          val success = Option(response.value)
          success.foreach(content => cache += path -> content)
          success.getOrElse(response.error)
        })
      } else
        new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
    FSReadFileResponse(uri, contents, null)
  }

  def readDirectory(
      uri: String
  ): Future[FSReadDirectoriesResponse] = Future {
    try {
      val subPaths = uri match {
        case URIMapper.parentURI =>
          Array(
            FSReadDirectoryResponse(URIMapper.jdkDir, false),
            FSReadDirectoryResponse(URIMapper.workspaceJarDir, false),
            FSReadDirectoryResponse(URIMapper.sourceJarDir, false),
          )
        case URIMapper.jdkURI =>
          uriMapper.getJDKs
            .map(jdk => FSReadDirectoryResponse(jdk, false))
            .toArray
        case URIMapper.workspaceJarURI =>
          uriMapper.getWorkspaceJars
            .map(jar => FSReadDirectoryResponse(jar, false))
            .toArray
        case URIMapper.sourceJarURI =>
          uriMapper.getSourceJars
            .map(jar => FSReadDirectoryResponse(jar, false))
            .toArray
        case jdk if jdk.startsWith(URIMapper.jdkURI) =>
          getLocalPathDirectory(
            jdk,
            URIMapper.jdkURI,
            uriMapper.getJDKFileSystem,
          )
        case workspaceJar
            if workspaceJar.startsWith(URIMapper.workspaceJarURI) =>
          getLocalPathDirectory(
            workspaceJar,
            URIMapper.workspaceJarURI,
            uriMapper.getWorkspaceJarFileSystem,
          )
        case sourceJar if sourceJar.startsWith(URIMapper.sourceJarURI) =>
          getLocalPathDirectory(
            sourceJar,
            URIMapper.sourceJarURI,
            uriMapper.getSourceJarFileSystem,
          )
      }
      FSReadDirectoriesResponse(uri, subPaths, null)
    } catch {
      case NonFatal(e) => FSReadDirectoriesResponse(uri, null, e.toString)
    }
  }

  def readFile(uri: String): Future[FSReadFileResponse] = Future {
    try {
      uri match {
        case jdk if jdk.startsWith(URIMapper.jdkURI) =>
          getLocalFileContents(
            jdk,
            URIMapper.jdkURI,
            uriMapper.getJDKFileSystem,
          )
        case workspaceJar
            if workspaceJar.startsWith(URIMapper.workspaceJarURI) =>
          getLocalFileContents(
            workspaceJar,
            URIMapper.workspaceJarURI,
            uriMapper.getWorkspaceJarFileSystem,
          )
        case sourceJar if sourceJar.startsWith(URIMapper.sourceJarURI) =>
          getLocalFileContents(
            sourceJar,
            URIMapper.sourceJarURI,
            uriMapper.getSourceJarFileSystem,
          )
        // VSCODE (or other clients) may ask for non-existant files (e.g. .gitignore)
        case _ => FSReadFileResponse(uri, null, "doesn't exist")
      }
    } catch {
      case NonFatal(e) => FSReadFileResponse(uri, null, e.toString)
    }
  }

  def getSystemStat(uri: String): Future[FSStatResponse] = Future {
    try {
      uri match {
        case URIMapper.parentURI =>
          FSStatResponse(URIMapper.rootDir, false, null)
        case URIMapper.jdkURI => FSStatResponse(URIMapper.jdkDir, false, null)
        case URIMapper.workspaceJarURI =>
          FSStatResponse(URIMapper.workspaceJarDir, false, null)
        case URIMapper.sourceJarURI =>
          FSStatResponse(URIMapper.sourceJarDir, false, null)
        case jdk if jdk.startsWith(URIMapper.jdkURI) =>
          getLocalSystemStat(
            jdk,
            URIMapper.jdkURI,
            uriMapper.getJDKFileSystem,
          )
        case workspaceJar
            if workspaceJar.startsWith(URIMapper.workspaceJarURI) =>
          getLocalSystemStat(
            workspaceJar,
            URIMapper.workspaceJarURI,
            uriMapper.getWorkspaceJarFileSystem,
          )
        case sourceJar if sourceJar.startsWith(URIMapper.sourceJarURI) =>
          getLocalSystemStat(
            sourceJar,
            URIMapper.sourceJarURI,
            uriMapper.getSourceJarFileSystem,
          )
      }
    } catch {
      case NonFatal(e) =>
        scribe.error(s"getSystemStat $uri", e)
        FSStatResponse(uri, false, e.toString())
    }
  }
}

import scala.collection.mutable
// TODO this is not thread safe - do we care?
class LRUCache[K, V](maxEntries: Int)
    extends java.util.LinkedHashMap[K, V](maxEntries, 1.0f, true) {

  override def removeEldestEntry(eldest: java.util.Map.Entry[K, V]): Boolean =
    size > maxEntries
}

object LRUCache {
  def apply[K, V](maxEntries: Int): mutable.Map[K, V] = {
    import scala.meta.internal.jdk.CollectionConverters._
    new LRUCache[K, V](maxEntries).asScala
  }
}
