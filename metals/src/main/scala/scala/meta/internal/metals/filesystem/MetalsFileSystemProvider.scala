package scala.meta.internal.metals.filesystem

import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.channels.SeekableByteChannel
import java.nio.file.AccessMode
import java.nio.file.CopyOption
import java.nio.file.DirectoryStream
import java.nio.file.FileStore
import java.nio.file.FileSystemAlreadyExistsException
import java.nio.file.FileSystemNotFoundException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.ProviderMismatchException
import java.nio.file.ReadOnlyFileSystemException
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.FileAttributeView
import java.nio.file.spi.FileSystemProvider
import java.util.stream.Collectors
import java.{util => ju}

import scala.collection.JavaConverters._

final case class MetalsFileSystemProvider() extends FileSystemProvider {

  private var fileSystem: MetalsFileSystem = _

  override def getScheme: String = MetalsFileSystemProvider.scheme

  override def newFileSystem(
      uri: URI,
      env: ju.Map[String, _]
  ): MetalsFileSystem = {
    if (getScheme != uri.getScheme)
      throw new ProviderMismatchException()
    synchronized {
      if (fileSystem != null)
        throw new FileSystemAlreadyExistsException(uri.toString)
      fileSystem = new MetalsFileSystem(this)
      fileSystem
    }
  }

  override def getFileSystem(uri: URI) = {
    if (getScheme != uri.getScheme)
      throw new ProviderMismatchException()
    synchronized {
      if (fileSystem == null)
        throw new FileSystemNotFoundException(uri.toString)
      else
        fileSystem
    }
  }

  override def getPath(uri: URI): Path = {
    val pathStr = uri.getSchemeSpecificPart()
    return getFileSystem(uri).getPath(pathStr)
  }

  override def newDirectoryStream(
      dir: Path,
      filter: DirectoryStream.Filter[_ >: Path]
  ): DirectoryStream[Path] = {
    dir match {
      case metalsPath: MetalsPath =>
        if (metalsPath == fileSystem.rootPath) {
          MetalsDirectoryStream(
            List(
              MetalsFileSystem.workspaceJarSubName,
              MetalsFileSystem.sourceJarSubName,
              MetalsFileSystem.jdkSubName
            ).map(name => metalsPath.resolve(name)),
            filter
          )
        } else if (!metalsPath.isInJar) {
          MetalsDirectoryStream(
            {
              metalsPath.getFileName().toString match {
                case MetalsFileSystem.workspaceJarSubName =>
                  fileSystem.getWorkspaceJars
                case MetalsFileSystem.sourceJarSubName =>
                  fileSystem.getSourceJars
                case MetalsFileSystem.jdkSubName => fileSystem.getJdks
              }
            }.map(name => metalsPath.resolve(name)),
            filter
          )
        } else
          MetalsDirectoryStream(
            fileSystem
              .accessLibrary(
                metalsPath,
                path =>
                  Files
                    .list(path)
                    .collect(Collectors.toList())
                    .asScala
                    .map(subPath =>
                      metalsPath.resolve(path.relativize(subPath))
                    )
                    .toList
              )
              .getOrElse(List.empty),
            filter
          )
      case _ => throw new ProviderMismatchException()
    }
  }

  override def newInputStream(path: Path, options: OpenOption*): InputStream =
    path match {
      case metalsPath: MetalsPath =>
        fileSystem
          .accessLibrary(
            metalsPath,
            path => Files.newInputStream(path, options: _*)
          )
          .getOrElse(throw new IOException(s"Can't access $path"))
      case _ => throw new ProviderMismatchException()
    }

  override def newByteChannel(
      path: Path,
      options: ju.Set[_ <: OpenOption],
      attrs: FileAttribute[_]*
  ): SeekableByteChannel = {
    path match {
      case metalsPath: MetalsPath =>
        if (!metalsPath.isInJar)
          throw new IOException(path.toUri.toString)
        else
          fileSystem
            .accessLibrary(
              metalsPath,
              path => Files.newByteChannel(path, options, attrs: _*)
            )
            .getOrElse(throw new IOException(s"$path"))
      case _ => throw new ProviderMismatchException()
    }
  }

  override def createDirectory(dir: Path, attrs: FileAttribute[_]*): Unit =
    throw new ReadOnlyFileSystemException()

  override def delete(path: Path): Unit =
    throw new ReadOnlyFileSystemException()

  override def copy(source: Path, target: Path, options: CopyOption*): Unit =
    throw new ReadOnlyFileSystemException()

  override def move(source: Path, target: Path, options: CopyOption*): Unit =
    throw new ReadOnlyFileSystemException()

  override def isSameFile(path: Path, path2: Path): Boolean =
    path.toAbsolutePath().equals(path2.toAbsolutePath())

  override def isHidden(path: Path): Boolean = false

  override def getFileStore(path: Path): FileStore = null

  override def checkAccess(path: Path, modes: AccessMode*): Unit = {
    if (modes.contains(AccessMode.WRITE))
      throw new IOException(s"Read only filesystem $path")
    path match {
      case metalsPath: MetalsPath =>
        if (metalsPath.isInJar)
          fileSystem
            .accessLibrary(
              metalsPath,
              path => path.getFileSystem().provider().checkAccess(path)
            )
      case _ => throw new ProviderMismatchException()
    }
  }

  override def getFileAttributeView[V <: FileAttributeView](
      path: Path,
      _type: Class[V],
      options: LinkOption*
  ): V = {
    path match {
      case metalsPath: MetalsPath =>
        if (!metalsPath.isInJar)
          throw new IOException(path.toUri.toString)
        else {
          fileSystem
            .accessLibrary(
              metalsPath,
              path => Files.getFileAttributeView(path, _type, options: _*)
            )
            .getOrElse(null.asInstanceOf[V])
        }
      case _ => throw new ProviderMismatchException()
    }
  }

  override def readAttributes[A <: BasicFileAttributes](
      path: Path,
      _type: Class[A],
      options: LinkOption*
  ): A = {
    path match {
      case metalsPath: MetalsPath =>
        if (!metalsPath.isInJar) {
          if (_type != classOf[BasicFileAttributes])
            throw new UnsupportedOperationException(
              s"readAttributes ${path.toUri} ${_type}"
            )
          new MetalsFileAttributes(metalsPath, false).asInstanceOf[A]
        } else
          fileSystem
            .accessLibrary(
              metalsPath,
              path => Files.readAttributes(path, _type, options: _*)
            )
            .getOrElse(throw new IOException(path.toUri.toString))
      case _ => throw new ProviderMismatchException()
    }
  }

  override def readAttributes(
      path: Path,
      attributes: String,
      options: LinkOption*
  ): ju.Map[String, Object] = {
    path match {
      case metalsPath: MetalsPath =>
        if (!metalsPath.isInJar)
          throw new IOException(path.toUri.toString)
        else
          fileSystem
            .accessLibrary(
              metalsPath,
              path => Files.readAttributes(path, attributes, options: _*)
            )
            .getOrElse(throw new IOException(path.toUri.toString))
      case _ => throw new ProviderMismatchException()
    }
  }

  override def setAttribute(
      path: Path,
      attribute: String,
      value: Object,
      options: LinkOption*
  ): Unit =
    throw new ReadOnlyFileSystemException()
}

object MetalsFileSystemProvider {
  val scheme: String = "metalsfs"
  val rootName: String = "/metalsLibraries"
  val separator: String = "/"
  val rootURI: URI = URI.create(s"${scheme}:$rootName")
  val rootNames: Array[String] = Array(rootName)
  val buildTargetsKey: String = "BuildTargets"
  val shellRunnerKey: String = "ShellRunner"
  val executionContextKey: String = "ExecutionContext"
}
