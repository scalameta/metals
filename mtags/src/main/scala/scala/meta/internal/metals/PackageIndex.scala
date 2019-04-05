package scala.meta.internal.metals

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.net.URLClassLoader
import java.nio.file.Paths
import java.util
import java.util.jar.JarFile
import java.util.logging.Level
import java.util.logging.Logger
import scala.meta.internal.io.PathIO
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.io.AbsolutePath
import scala.meta.io.Classpath
import scala.util.control.NonFatal

/**
 * An index to lookup classfiles contained in a given classpath.
 */
class PackageIndex() {
  val logger = Logger.getLogger(classOf[PackageIndex].getName)
  val packages = new util.HashMap[String, util.ArrayList[String]]()
  private val isVisited = new util.HashSet[AbsolutePath]()
  private val enterPackage =
    new util.function.Function[String, util.ArrayList[String]] {
      override def apply(t: String): util.ArrayList[String] = {
        new util.ArrayList[String]()
      }
    }
  def visit(entry: AbsolutePath): Unit = {
    if (isVisited.contains(entry)) ()
    else {
      isVisited.add(entry)
      try {
        if (entry.isDirectory) {
          visitDirectoryEntry(entry)
        } else if (entry.isFile && entry.extension == "jar") {
          visitJarEntry(entry)
        }
      } catch {
        case NonFatal(e) =>
          logger.log(Level.SEVERE, entry.toURI.toString, e)
      }
    }
  }

  def addMember(pkg: String, member: String): Unit = {
    if (!member.contains("module-info.class")) {
      val members = packages.computeIfAbsent(pkg, enterPackage)
      members.add(member)
    }
  }

  private def visitDirectoryEntry(dir: AbsolutePath): Unit = {
    Files.walkFileTree(
      dir.toNIO,
      new SimpleFileVisitor[Path] {
        override def visitFile(
            file: Path,
            attrs: BasicFileAttributes
        ): FileVisitResult = {
          val member = file.getFileName.toString
          if (member.endsWith(".class")) {
            val relpath = AbsolutePath(file.getParent).toRelative(dir)
            val pkg = relpath.toURI(isDirectory = true).toString
            addMember(pkg, member)
          }
          FileVisitResult.CONTINUE
        }
        override def preVisitDirectory(
            dir: Path,
            attrs: BasicFileAttributes
        ): FileVisitResult = {
          if (dir.endsWith("META-INF")) FileVisitResult.SKIP_SUBTREE
          else FileVisitResult.CONTINUE
        }
      }
    )
  }
  private def visitJarEntry(jarpath: AbsolutePath): Unit = {
    val file = jarpath.toFile
    val jar = new JarFile(file)
    try {
      val entries = jar.entries()
      while (entries.hasMoreElements) {
        val element = entries.nextElement()
        if (!element.isDirectory &&
          !element.getName.startsWith("META-INF") &&
          element.getName.endsWith(".class")) {
          val pkg = PathIO.dirname(element.getName)
          val member = PathIO.basename(element.getName)
          addMember(pkg, member)
        }
      }
      val manifest = jar.getManifest
      if (manifest != null) {
        val classpathAttr = manifest.getMainAttributes.getValue("Class-Path")
        if (classpathAttr != null) {
          classpathAttr.split(" ").foreach { relpath =>
            val abspath = AbsolutePath(jarpath.toNIO.getParent).resolve(relpath)
            if (abspath.isFile || abspath.isDirectory) {
              visit(abspath)
            }
          }
        }
      }
    } finally {
      jar.close()
    }
  }

  def visitBootClasspath(): Unit = {
    PackageIndex.bootClasspath.foreach(visit)
    PackageIndex.scalaLibrary.foreach { scalaLibrary =>
      visit(AbsolutePath(scalaLibrary))
    }
  }

}

object PackageIndex {
  def bootClasspath: List[AbsolutePath] =
    for {
      entries <- sys.props.collectFirst {
        case (k, v) if k.endsWith(".boot.class.path") =>
          Classpath(v).entries
      }.toList
      entry <- entries
      if entry.isFile
    } yield entry

  def scalaLibrary: Seq[Path] = {
    this.getClass.getClassLoader
      .asInstanceOf[URLClassLoader]
      .getURLs
      .iterator
      .filter(_.getPath.contains("scala-library"))
      .map(url => Paths.get(url.toURI))
      .toSeq
  }
}
