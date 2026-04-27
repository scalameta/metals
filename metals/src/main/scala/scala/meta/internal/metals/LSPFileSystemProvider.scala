package scala.meta.internal.metals

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.stream.Collectors

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.mtags.URIEncoderDecoder

/** Response indicating whether an entry is a file or directory. */
case class FSReadDirectoryResponse(name: String, isFile: Boolean)

/** Response containing the contents of a directory listing. */
case class FSReadDirectoriesResponse(
    name: String,
    directories: Array[FSReadDirectoryResponse],
    error: String,
)

/** Response containing the textual contents of a file. */
case class FSReadFileResponse(name: String, value: String, error: String)

/** Response containing file metadata (existence and type). */
case class FSStatResponse(name: String, isFile: Boolean, error: String)

/**
 * Handles virtual file system requests from the LSP client.
 *
 * Translates `metalsfs://` URIs into JAR file system reads using Java NIO,
 * delegating URI resolution to [[URIMapper]] and `.class` decompilation
 * to [[FileDecoderProvider]].
 */
class LSPFileSystemProvider(
    languageClient: MetalsLanguageClient,
    uriMapper: URIMapper,
    fileDecoderProvider: FileDecoderProvider,
    clientConfig: ClientConfiguration,
)(implicit ec: ExecutionContext) {

  /** Notifies the client that the library file system is ready for use. */
  def sendLibraryFileSystemReady(): Unit = {
    if (clientConfig.isLibraryFileSystemSupported()) {
      val params =
        ClientCommands.LibraryFileSystemReady.toExecuteCommandParams()
      languageClient.metalsExecuteClientCommand(params)
    }
  }

  /**
   * Lists the contents of a virtual directory.
   *
   * For top-level URIs returns the three root categories (jdk, jar, source).
   * For category URIs returns the available archives from [[BuildTargets]].
   * For paths within an archive opens the corresponding NIO file system
   * and lists the directory entries.
   */
  def readDirectory(uri: String): Future[FSReadDirectoriesResponse] = Future {
    val entries = uri match {
      case URIMapper.parentURI =>
        Array(
          FSReadDirectoryResponse(URIMapper.jdkDir, isFile = false),
          FSReadDirectoryResponse(URIMapper.workspaceJarDir, isFile = false),
          FSReadDirectoryResponse(URIMapper.sourceJarDir, isFile = false),
        )
      case URIMapper.jdkURI =>
        uriMapper.getJDKs
          .map(name => FSReadDirectoryResponse(name, isFile = false))
          .toArray
      case URIMapper.workspaceJarURI =>
        uriMapper.getWorkspaceJars
          .map(name => FSReadDirectoryResponse(name, isFile = false))
          .toArray
      case URIMapper.sourceJarURI =>
        uriMapper.getSourceJars
          .map(name => FSReadDirectoryResponse(name, isFile = false))
          .toArray
      case _ =>
        val (fs, innerPath) = resolveInnerPath(uri)
        val path = fs.fs.getPath(innerPath.getOrElse("/"))
        Files
          .list(path)
          .collect(Collectors.toList())
          .asScala
          .map(p =>
            FSReadDirectoryResponse(
              p.getFileName.toString,
              isFile = Files.isRegularFile(p),
            )
          )
          .toArray
    }
    FSReadDirectoriesResponse(uri, entries, "")
  }

  /**
   * Reads the textual contents of a file inside a JAR archive.
   *
   * For `.class` files the bytecode is decompiled via CFR through
   * [[FileDecoderProvider]]. All other files are read as UTF-8 text.
   */
  def readFile(uri: String): Future[FSReadFileResponse] = Future {
    val (fs, innerPath) = resolveInnerPath(uri)
    val path = fs.fs.getPath(innerPath.getOrElse("/"))
    val contents =
      if (path.getFileName.toString.endsWith(".class")) {
        val fut = fileDecoderProvider.decodedFileContents(path.toUri.toString)
        val res = Await.result(fut, 10.minutes)
        Option(res.value).filter(_.nonEmpty).getOrElse(res.error)
      } else {
        new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
      }
    FSReadFileResponse(uri, contents, "")
  }

  /**
   * Returns metadata for the given URI, indicating whether it
   * represents a file or a directory.
   *
   * Known root-level URIs are resolved statically; paths within
   * an archive are checked via the NIO file system.
   */
  def getSystemStat(uri: String): Future[FSStatResponse] = Future {
    uri match {
      case URIMapper.parentURI =>
        FSStatResponse(URIMapper.rootDir, isFile = false, "")
      case URIMapper.jdkURI =>
        FSStatResponse(URIMapper.jdkDir, isFile = false, "")
      case URIMapper.workspaceJarURI =>
        FSStatResponse(URIMapper.workspaceJarDir, isFile = false, "")
      case URIMapper.sourceJarURI =>
        FSStatResponse(URIMapper.sourceJarDir, isFile = false, "")
      case _ =>
        val (fs, innerPath) = resolveInnerPath(uri)
        val path = fs.fs.getPath(innerPath.getOrElse("/"))
        FSStatResponse(uri, isFile = Files.isRegularFile(path), "")
    }
  }

  /**
   * Resolves a `metalsfs://` URI into a NIO [[FileSystemInfo]] and
   * an optional inner path within the archive.
   *
   * Dispatches to the appropriate [[URIMapper]] method based on whether
   * the URI falls under the jdk, workspace jar, or source jar category.
   */
  private def resolveInnerPath(
      uri: String
  ): (FileSystemInfo, Option[String]) = {
    val decoded = URIEncoderDecoder.decode(uri)
    decoded match {
      case jdk if jdk.startsWith(URIMapper.jdkURI) =>
        val (name, path) = URIMapper.getURIParts(jdk, URIMapper.jdkURI)
        (uriMapper.getJDKFileSystem(name), path)
      case jar if jar.startsWith(URIMapper.workspaceJarURI) =>
        val (name, path) =
          URIMapper.getURIParts(jar, URIMapper.workspaceJarURI)
        (uriMapper.getWorkspaceJarFileSystem(name), path)
      case src if src.startsWith(URIMapper.sourceJarURI) =>
        val (name, path) =
          URIMapper.getURIParts(src, URIMapper.sourceJarURI)
        (uriMapper.getSourceJarFileSystem(name), path)
    }
  }
}
