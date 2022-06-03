package scala.meta.internal.metals.filesystem

import java.io.File
import java.net.URI
import java.nio.file.LinkOption
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.ProviderMismatchException
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.{util => ju}

import scala.collection.JavaConverters._
import scala.annotation.tailrec
import scala.meta.io.RelativePath

final case class MetalsPath(
    metalsFileSystem: MetalsFileSystem,
    names: Array[String]
) extends Path {

  override def getFileSystem: MetalsFileSystem = metalsFileSystem

  override def isAbsolute: Boolean =
    names.nonEmpty && names(0) == MetalsFileSystemProvider.rootName

  override def getRoot: MetalsPath =
    if (isAbsolute())
      metalsFileSystem.rootPath
    else
      null

  override def getFileName: MetalsPath =
    if (names.isEmpty)
      null
    else if (names.length == 1)
      this
    else MetalsPath(metalsFileSystem, Array(names.last))

  override def getParent: MetalsPath =
    if (names.length < 2)
      null
    else if (isAbsolute() && names.length == 2)
      getRoot
    else
      MetalsPath(metalsFileSystem, names.take(names.length - 1))

  override def getNameCount: Int = names.size

  override def getName(index: Int): MetalsPath =
    if (isAbsolute && names.length == 1 && index == 0)
      getRoot
    else if (index >= names.length)
      throw new IllegalArgumentException()
    else
      MetalsPath(metalsFileSystem, Array(names(index)))

  override def subpath(beginIndex: Int, endIndex: Int): MetalsPath =
    if (isAbsolute && names.length == 1 && beginIndex == 0 && endIndex == 1)
      getRoot
    else if (beginIndex >= endIndex)
      throw new IllegalArgumentException()
    else if (beginIndex < 0)
      throw new IllegalArgumentException()
    else if (endIndex > names.length)
      throw new IllegalArgumentException()
    else if (beginIndex == 0 && endIndex == names.length)
      this
    else
      MetalsPath(metalsFileSystem, names.slice(beginIndex, endIndex))

  private def checkPath[F](path: Path, apply: MetalsPath => F): F =
    if (path == null)
      throw new NullPointerException()
    else
      path match {
        case metalsPath: MetalsPath => apply(metalsPath)
        case _ => throw new ProviderMismatchException()
      }

  override def startsWith(other: Path): Boolean =
    checkPath(
      other,
      metalsPath => {
        if (this == other)
          true
        else if (
          isAbsolute() != metalsPath.isAbsolute() ||
          names.length < metalsPath.names.length
        )
          false
        else {
          var idx = metalsPath.names.length - 1
          var different = false
          while (!different && idx >= 0) {
            if (names(idx) != metalsPath.names(idx))
              different = true
            idx = idx - 1
          }
          !different
        }
      }
    )

  override def startsWith(other: String): Boolean =
    startsWith(getFileSystem().getPath(other))

  override def endsWith(other: Path): Boolean =
    checkPath(
      other,
      metalsPath => {
        if (this == other)
          true
        else if (
          metalsPath.isAbsolute() ||
          names.length < metalsPath.names.length
        )
          false
        else {
          var idx = metalsPath.names.length - 1
          var different = false
          while (!different && idx >= 0) {
            if (
              names(idx + names.length - metalsPath.names.length) != metalsPath
                .names(idx)
            )
              different = true
            idx = idx - 1
          }
          !different
        }
      }
    )

  override def endsWith(other: String): Boolean =
    return endsWith(getFileSystem().getPath(other))

  override def normalize(): MetalsPath = {
    val count = names.count(f => f == "." || f == "..")
    if (count == 0)
      this
    else {
      val newNames = new Array[String](names.length - count)
      var oldIdx = names.length - 1
      var newIdx = newNames.length - 1
      var doubleDotCount = 0
      while (oldIdx >= 0) {
        if (names(oldIdx) == ".")
          oldIdx = oldIdx - 1
        else if (names(oldIdx) == "..") {
          doubleDotCount = doubleDotCount + 1
          oldIdx = oldIdx - 1
        } else if (doubleDotCount > 0) {
          doubleDotCount = doubleDotCount - 1
          oldIdx = oldIdx - 1
        } else {
          newNames(newIdx) = names(oldIdx)
          oldIdx = oldIdx - 1
          newIdx = newIdx - 1
        }
      }
      MetalsPath(metalsFileSystem, newNames)
    }
  }

  override def resolve(other: Path): MetalsPath =
    if (other.isAbsolute()) {
      other match {
        case mp: MetalsPath => mp
        case _ => throw new ProviderMismatchException()
      }
    } else {
      val newNames = new Array[String](names.length + other.getNameCount)
      var idx = 0
      while (idx < names.length) {
        newNames(idx) = names(idx)
        idx = idx + 1
      }
      idx = 0
      while (idx < other.getNameCount) {
        newNames(idx + names.length) = other.getName(idx).toString
        idx = idx + 1
      }
      MetalsPath(metalsFileSystem, newNames)
    }

  override def resolve(other: String): MetalsPath =
    resolve(getFileSystem().getPath(other))

  override def resolveSibling(other: Path): MetalsPath =
    checkPath(
      other,
      metalsPath => {
        val parent = getParent();
        if (parent == null) metalsPath else parent.resolve(other)
      }
    )

  override def resolveSibling(other: String): MetalsPath =
    resolveSibling(getFileSystem().getPath(other))

  override def relativize(other: Path): MetalsPath = {
    if (isAbsolute() != other.isAbsolute())
      throw new IllegalArgumentException(
        s"Cannot relativize ${this.toUri()} with ${other.toUri()}"
      )
    if (getNameCount() > other.getNameCount())
      throw new IllegalArgumentException(
        s"Cannot relativize ${this.toUri()} with ${other.toUri()}"
      )
    // TODO both being !absolute is valid but what's it supposed to return?
    if (!isAbsolute())
      throw new UnsupportedOperationException("relativize")
    checkPath(
      other,
      metalsPath => {
        var i = 0
        var matching = true
        while (matching && i < names.length) {
          if (names(i) != metalsPath.names(i))
            matching = false
          i = i + 1
        }
        if (!matching)
          throw new IllegalArgumentException(
            s"Cannot relativize ${this.toUri()} with ${other.toUri()}"
          )
        val newNames = new Array[String](metalsPath.names.length - names.length)
        var idx = names.length
        while (idx < metalsPath.names.length) {
          newNames(idx - names.length) = metalsPath.names(idx)
          idx = idx + 1
        }
        MetalsPath(metalsFileSystem, newNames)
      }
    )
  }

  override def toUri: URI = {
    try {
      if (isAbsolute()) {
        if (names.lengthCompare(1) == 0)
          MetalsFileSystemProvider.rootURI
        else {
          val uriAsStr =
            s"${MetalsFileSystemProvider.scheme}:${names.mkString(MetalsFileSystemProvider.separator)}"
          URI.create(uriAsStr)
        }
      } else
        toAbsolutePath.toUri
    } catch {
      case e: Exception =>
        scribe.error("toURI error ", e)
        throw e
    }
  }

  override def toAbsolutePath: MetalsPath =
    if (isAbsolute())
      this
    else
      metalsFileSystem.rootPath.resolve(this)

  override def toRealPath(options: LinkOption*): MetalsPath =
    toAbsolutePath.normalize

  override def toFile: File = {
    throw new UnsupportedOperationException("toFile")
  }

  override def register(
      watcher: WatchService,
      events: Array[WatchEvent.Kind[_]],
      modifiers: WatchEvent.Modifier*
  ) = throw new UnsupportedOperationException("register")

  override def register(
      watcher: WatchService,
      events: WatchEvent.Kind[_]*
  ): WatchKey = throw new UnsupportedOperationException("register")

  override def iterator(): ju.Iterator[Path] = {
    val paths = names
      .map(name => MetalsPath(metalsFileSystem, Array(name)): Path)
      .toIterator
    if (isAbsolute())
      paths.drop(1).asJava
    else
      paths.asJava
  }

  override def compareTo(other: Path): Int =
    checkPath(
      other,
      metalsPath => {
        var result = 0
        var idx = 0
        while (
          result == 0 && idx < names.length && idx < metalsPath.names.length
        ) {
          val nameCompare = names(idx).compareTo(metalsPath.names(idx))
          if (nameCompare != 0)
            result = nameCompare
          idx = idx + 1
        }
        if (result == 0)
          names.length.compareTo(metalsPath.names.length)
        else
          result
      }
    )

  override def hashCode: Int =
    names.toSeq.hashCode

  override def equals(other: Any): Boolean =
    other match {
      case otherPath: MetalsPath =>
        metalsFileSystem == otherPath.metalsFileSystem &&
        names.sameElements(otherPath.names)
      case _ => false
    }

  override def toString(): String =
    names.mkString(MetalsFileSystemProvider.separator)

  def isJDK: Boolean =
    names.lengthCompare(2) >= 0 && names(2) == MetalsFileSystem.jdkSubName

  def isInJar: Boolean = names.lengthCompare(3) >= 0

  // metalsfs:/xxx/name.jar/package/class -> metalsfs:/xxx/name.jar
  def jarPath: Option[MetalsPath] = {
    @tailrec
    def jarPath(path: MetalsPath): MetalsPath =
      if (path.names.lengthCompare(3) == 0)
        path
      else jarPath(path.getParent)
    if (names.lengthCompare(2) < 0) None else Some(jarPath(this))
  }

  // metalsfs:/xxx/name.jar/package/class -> jar:/yyy/name.jar!/package/class
  def originalJarURI: Option[URI] =
    metalsFileSystem.getOriginalJarURI(this)

  def jarName: Option[String] =
    if (names.lengthCompare(3) < 0) None else Some(names(2))

  def jarDirs: Seq[String] =
    if (names.lengthCompare(3) < 0) Seq.empty else names.toSeq.drop(3)

  def moduleName: Option[String] =
    if (names.lengthCompare(3) < 0) None
    else {
      // jdk uses module/module-info.java
      val moduleLocationNames = new Array[String](5)
      var i = 0
      while (i < 4) {
        moduleLocationNames(i) = names(i)
        i = i + 1
      }
      moduleLocationNames(4) = "module-info.java"
      val moduleInfoLocation = MetalsPath(metalsFileSystem, moduleLocationNames)
      val tmp = if (Files.exists(moduleInfoLocation)) {
        Some(moduleLocationNames(3))
      } else None
      // TODO - how to find out module name from non-jdk jar??? We've only tested with src.zip
      scribe.error(s"Module info for ${moduleInfoLocation.toUri()} is $tmp")
      tmp
    }

}
object MetalsPath {
  def fromReadOnly(relativePath: RelativePath): MetalsPath =
    MetalsFileSystem.metalsFS.rootPath.resolve(relativePath.toNIO)
}
