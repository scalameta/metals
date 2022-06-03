package scala.meta.internal.metals.filesystem

import java.net.URI
import java.net.URL
import java.nio.file.FileStore
import java.nio.file.FileSystem
import java.nio.file.FileSystemNotFoundException
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.Paths
import java.nio.file.ProviderNotFoundException
import java.nio.file.WatchService
import java.nio.file.attribute.UserPrincipalLookupService
import java.nio.file.spi.FileSystemProvider
import java.{lang => jl}
import java.{util => ju}

import scala.annotation.tailrec
import scala.collection.concurrent.TrieMap
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.FileDecoderProvider
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

class MetalsFileSystem(fileSystemProvider: MetalsFileSystemProvider)
    extends FileSystem {

  // TODO work out when to reset this - on a new build import - or just built up as build targets gets new jars?
  private val workspaceJars = TrieMap.empty[String, JarInfo]
  private val sourceJars = TrieMap.empty[String, JarInfo]
  private val jdks = TrieMap.empty[String, JarInfo]
  private val metalsPathToJarInfo = TrieMap.empty[String, JarInfo]

  private var shellRunner: ShellRunner = _
  private var buildTargets: BuildTargets = _
  private var executionContext: ExecutionContext = _

  def setShellRunner(
      shellRunner: ShellRunner,
      buildTargets: BuildTargets,
      executionContext: ExecutionContext
  ): Unit = {
    this.shellRunner = shellRunner
    this.buildTargets = buildTargets
    this.executionContext = executionContext
  }

  val rootPath: MetalsPath =
    MetalsPath(this, MetalsFileSystemProvider.rootNames)

  private val rootDirectories: jl.Iterable[Path] =
    ju.Collections.singletonList(rootPath)

  override def provider(): FileSystemProvider = fileSystemProvider

  override def close(): Unit = {}

  override def isOpen(): Boolean = true

  override def isReadOnly(): Boolean = true

  override def getSeparator(): String = MetalsFileSystemProvider.separator

  override def getRootDirectories(): jl.Iterable[Path] = rootDirectories

  override def getFileStores(): jl.Iterable[FileStore] = null

  override def supportedFileAttributeViews(): ju.Set[String] = null

  override def getPath(first: String, more: String*): Path = {
    def tidyPath(path: String): String = {
      if (!path.startsWith("//"))
        path
      else
        tidyPath(path.stripPrefix("/"))
    }
    val pathAsStr = List(List(first), more).flatten
      .mkString(MetalsFileSystemProvider.separator)
    // handle both absolute paths "/root/foo/bar" and relative "foo/bar"
    // URLs will request a path of "///xxxx" so remove additional "/"
    val tidiedPath = tidyPath(pathAsStr)
    val items = if (tidiedPath.startsWith(MetalsFileSystemProvider.separator)) {
      val split = tidiedPath.drop(1).split(MetalsFileSystemProvider.separator)
      split(0) = s"${MetalsFileSystemProvider.separator}${split(0)}"
      split
    } else
      tidiedPath.split(MetalsFileSystemProvider.separator)
    val names = new Array[String](items.length)
    items.zipWithIndex.foreach(f => names(f._2) = f._1)
    MetalsPath(this, names)
  }

  // TODO is default file system the right one to use? or re-implement
  // https://docs.oracle.com/javase/8/docs/api/java/nio/file/FileSystem.html#getPathMatcher-java.lang.String-
  override def getPathMatcher(syntaxAndPattern: String): PathMatcher =
    FileSystems.getDefault().getPathMatcher(syntaxAndPattern)

  override def getUserPrincipalLookupService(): UserPrincipalLookupService =
    throw new UnsupportedOperationException("getUserPrincipalLookupService")

  override def newWatchService(): WatchService =
    throw new UnsupportedOperationException("newWatchService")

  private def createJarInfo(
      filename: String,
      subName: String,
      path: AbsolutePath
  ): JarInfo = {
    val names = new Array[String](3)
    names(0) = MetalsFileSystemProvider.rootName
    names(1) = subName
    names(2) = filename
    val metalsPath = MetalsPath(this, names)
    JarInfo(
      names.mkString(getSeparator),
      URI.create(s"jar:${path.toNIO.toUri.toString}"),
      path.toNIO.toUri.toURL.getPath,
      metalsPath,
      new ju.HashMap[String, Any]
    )
  }

  private def getOrElseUpdateJar(
      jarMap: TrieMap[String, JarInfo],
      filename: String,
      subName: String,
      jarPath: AbsolutePath
  ): AbsolutePath = {
    val jarInfo = jarMap
      .getOrElseUpdate(filename, createJarInfo(filename, subName, jarPath))
    metalsPathToJarInfo.update(jarInfo.metalsJarPath, jarInfo)
    jarInfo.metalsAbsolutePath
  }

  def getOrElseUpdateWorkspaceJar(path: AbsolutePath): AbsolutePath =
    getOrElseUpdateJar(
      workspaceJars,
      path.filename,
      MetalsFileSystem.workspaceJarSubName,
      path
    )

  def getOrElseUpdateSourceJar(path: AbsolutePath): AbsolutePath =
    getOrElseUpdateJar(
      sourceJars,
      path.filename,
      MetalsFileSystem.sourceJarSubName,
      path
    )

  def getOrElseUpdateJDK(path: AbsolutePath): AbsolutePath = {

    def findDecentJDKName(path: AbsolutePath): AbsolutePath =
      if (!path.hasParent || !List("lib", "src.zip").contains(path.filename))
        path
      else
        findDecentJDKName(path.parent)

    val filename = findDecentJDKName(path).filename
    getOrElseUpdateJar(jdks, filename, MetalsFileSystem.jdkSubName, path)
  }

  def getWorkspaceJars: Iterable[String] = workspaceJars.keySet

  def getSourceJars: Iterable[String] = sourceJars.keySet

  def getJdks: Iterable[String] = jdks.keySet

  def getWorkspaceJarPaths: Iterable[AbsolutePath] =
    workspaceJars.values.map(_.metalsAbsolutePath)

  // metalsfs:/xxx/name.jar#runtime -> jar:/yyy/name.jar#runtime
  // metalsfs:/xxx/name.jar/package/class -> jar:/yyy/name.jar!/package/class
  def getOriginalJarURL(url: URL): Option[URL] = {
    // convert to uri this way to remove fragments
    val metalsURI =
      new URI(url.getProtocol(), url.getHost(), url.getPath(), null)
    Paths.get(metalsURI) match {
      case metalsPath: MetalsPath =>
        getOriginalJarURI(metalsPath).map(uri => {
          val uriWithFragments =
            if (url.getRef != null && url.getRef.nonEmpty) {
              val strippedPath = uri.getPath().stripSuffix("!/")
              new URI(uri.getScheme(), uri.getHost(), strippedPath, url.getRef)
            } else
              uri
          uriWithFragments.toURL()
        })
      case _ => throw new ProviderNotFoundException(url.toString())
    }
  }

  // metalsfs:/xxx/name.jar/package/class -> jar:/yyy/name.jar!/package/class
  // metalsfs:/xxx/name.jar -> jar:/yyy/name.jar
  def getOriginalJarURI(metalsPath: MetalsPath): Option[URI] = {
    metalsPath.jarName.flatMap(jarName => {
      workspaceJars
        .get(jarName)
        .orElse(sourceJars.get(jarName))
        .orElse(jdks.get(jarName))
        .map(jarInfo => {
          val jarDirs = metalsPath.jarDirs
          URI.create(
            s"jar:file://${jarInfo.jarURLFile}!/${jarDirs.mkString(jarInfo.jarFS.getSeparator)}"
          )
        })
    })
  }

  // jar:/yyy/name.jar!/package/class -> metalsfs:/xxx/name.jar/package/class
  def getMetalsJarPath(uri: URI): Option[AbsolutePath] = {
    @tailrec
    def resolve(path: Path, dirs: Seq[String]): Path =
      if (dirs.isEmpty)
        path
      else
        resolve(path.resolve(dirs.head), dirs.tail)
    val path = uri.toAbsolutePath
    path.jarPath.flatMap(jarPath => {
      val jarName = jarPath.filename
      workspaceJars
        .get(jarName)
        .orElse(sourceJars.get(jarName))
        .orElse(jdks.get(jarName))
        .map(jarInfo =>
          jarInfo.metalsAbsolutePath.resolve(path.toString.stripPrefix("/"))
        )
    })
  }

  def accessLibrary[A](
      path: MetalsPath,
      convert: Path => A
  ): Option[A] = {

    @tailrec
    def resolve(path: Path, dirs: Seq[String]): Path =
      if (dirs.isEmpty)
        path
      else
        resolve(path.resolve(dirs.head), dirs.tail)

    if (!path.isAbsolute())
      accessLibrary(path.toAbsolutePath(), convert)
    else
      path.jarName.flatMap(jarName => {
        val jarDirs = path.jarDirs
        workspaceJars
          .get(jarName)
          .orElse(sourceJars.get(jarName))
          .orElse(jdks.get(jarName))
          .map(jarInfo => {
            val inJarPath = resolve(
              jarInfo.jarFS.getPath(jarInfo.jarFS.getSeparator),
              jarDirs
            )
            convert(inJarPath)
          })
      })
  }

  // cache the last few decompiled class files as they're repeatedly accessed by metals once opened or referenced
  private val cache = LRUCache[Path, Some[String]](10)

  def decodeCFRFromClassFile(path: Path): Option[String] = {
    if (shellRunner == null) {
      scribe.info("Wait for Metals to finish indexing")
      None
    } else {
      val cacheResult = cache.get(path)
      cacheResult.getOrElse({
        val result =
          FileDecoderProvider
            .decodeCFRFromClassFile(
              shellRunner,
              buildTargets,
              AbsolutePath(path)
            )(executionContext)
        val response = Await.result(result, Duration("10min"))
        val success = Option(response.value)
        success.foreach(content => cache += path -> Some(content))
        success.orElse(Option(response.error))
      })
    }
  }

  def isMetalsFileSystem(path: Path): Boolean =
    path match {
      case _: MetalsPath => true
      case _ => false
    }
}

object MetalsFileSystem {
  val jdkSubName: String = "jdk"
  val workspaceJarSubName: String = "jar"
  val sourceJarSubName: String = "source"

  private var _metalsFS: MetalsFileSystem = _

  def metalsFS: MetalsFileSystem = {
    if (_metalsFS == null)
      synchronized {
        if (_metalsFS == null)
          _metalsFS = FileSystems.newFileSystem(
            MetalsFileSystemProvider.rootURI,
            ju.Collections.emptyMap[String, String]
          ) match {
            case mfs: MetalsFileSystem => mfs
            case _ =>
              throw new ProviderNotFoundException(
                MetalsFileSystemProvider.rootURI.toString()
              )
          }
      }
    _metalsFS
  }
}

final case class JarInfo(
    metalsAbsolutePath: AbsolutePath,
    jarFS: FileSystem,
    jarURLFile: String,
    metalsJarPath: String
)

object JarInfo {
  def apply(
      metalsJarPath: String,
      jarURI: URI,
      jarURLFile: String,
      metalsPath: MetalsPath,
      envMap: ju.Map[String, Any]
  ): JarInfo = {
    val jarFS =
      try {
        FileSystems.getFileSystem(jarURI)
      } catch {
        case _: FileSystemNotFoundException =>
          FileSystems.newFileSystem(jarURI, envMap)
      }
    JarInfo(AbsolutePath(metalsPath), jarFS, jarURLFile, metalsJarPath)
  }
}

import scala.collection.mutable
// TODO there must be one of these somewhere already in the Metals codebase
// TODO this is not thread safe
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
