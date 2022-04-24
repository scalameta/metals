package scala.meta.internal.metals

import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.control.NonFatal

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.filesystem.MetalsFileSystem
import scala.meta.internal.metals.filesystem.MetalsFileSystemProvider

final case class FSReadDirectoryResponse(
    name: String,
    isFile: Boolean
)
final case class FSReadDirectoriesResponse(
    name: String,
    directories: Array[FSReadDirectoryResponse],
    error: String
)

final case class FSReadFileResponse(
    name: String,
    value: String,
    error: String
)

final case class FSStatResponse(
    name: String,
    isFile: Boolean,
    error: String
)

final class LSPFileSystemProvider(
    languageClient: MetalsLanguageClient,
    clientConfig: ClientConfiguration
)(implicit ec: ExecutionContext) {

  private def getPath(uriAsStr: String): Path =
    Paths.get(URI.create(uriAsStr))

  def createLibraryFileSystem(): Unit =
    if (clientConfig.isLibraryFileSystemSupported()) {
      val params =
        ClientCommands.CreateLibraryFileSystem.toExecuteCommandParams(
          MetalsFileSystemProvider.rootURI.toString()
        )
      languageClient.metalsExecuteClientCommand(params)
    }

  def readDirectory(
      uriAsStr: String
  ): Future[FSReadDirectoriesResponse] = Future {
    try {
      val path = getPath(uriAsStr)
      val subPaths = Files
        .list(path)
        .map(subPath =>
          FSReadDirectoryResponse(
            subPath.getFileName().toString(),
            Files.isRegularFile(subPath)
          )
        )
        .collect(Collectors.toList())
        .asScala
        .toArray
      FSReadDirectoriesResponse(uriAsStr, subPaths, null)
    } catch {
      case NonFatal(e) => FSReadDirectoriesResponse(uriAsStr, null, e.toString)
    }
  }

  def readFile(uriAsStr: String): Future[FSReadFileResponse] = Future {
    try {
      val path = getPath(uriAsStr)
      val contents = if (path.isClassfile) {
        MetalsFileSystem.metalsFS
          .decodeCFRFromClassFile(path)
          .getOrElse(s"Unable to decode class file ${path.toUri}")
      } else
        new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
      FSReadFileResponse(uriAsStr, contents, null)
    } catch {
      case NonFatal(e) => FSReadFileResponse(uriAsStr, null, e.toString)
    }
  }

  def getSystemStat(uriAsStr: String): Future[FSStatResponse] = Future {
    try {
      val path = getPath(uriAsStr)
      val filename = path.getFileName.toString
      val isFile = Files.isRegularFile(path)
      FSStatResponse(filename, isFile, null)
    } catch {
      case NonFatal(e) => FSStatResponse(uriAsStr, false, e.toString())
    }
  }
}
