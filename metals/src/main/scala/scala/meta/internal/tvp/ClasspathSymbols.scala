package scala.meta.internal.tvp

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.tools.asm.tree.ClassNode
import scala.tools.scalap.scalax.rules.scalasig.SymbolInfoSymbol
import scala.util.control.NonFatal

import scala.meta.internal.classpath.ClasspathIndex
import scala.meta.internal.io._
import scala.meta.internal.javacp.Javacp
import scala.meta.internal.metacp._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.Time
import scala.meta.internal.metals.Timer
import scala.meta.internal.mtags.Symbol
import scala.meta.internal.scalacp.SymlinkChildren
import scala.meta.internal.scalacp.Synthetics
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.Scala.{Descriptor => d}
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.internal.semanticdb.SymbolInformation.{Kind => k}
import scala.meta.internal.semanticdb._
import scala.meta.io.AbsolutePath
import scala.meta.io.Classpath

/**
 * Extracts SemanticDB symbols from `*.class` files in jars.
 */
class ClasspathSymbols(isStatisticsEnabled: Boolean = false) {
  private val cache = TrieMap.empty[
    AbsolutePath,
    TrieMap[String, Array[TreeViewSymbolInformation]]
  ]

  def clearCache(path: AbsolutePath): Unit = {
    cache.remove(path)
  }

  def reset(): Unit = {
    cache.clear()
  }

  def symbols(
      in: AbsolutePath,
      symbol: String
  ): Iterator[TreeViewSymbolInformation] = {
    val symbols = cache.getOrElseUpdate(in, TrieMap.empty)
    symbols
      .getOrElseUpdate(
        symbol.asSymbol.enclosingPackage.value, {
          val timer = new Timer(Time.system)
          val result = loadSymbols(in, symbol)
          if (isStatisticsEnabled) {
            scribe.info(s"$timer - $in/!$symbol")
          }
          result
        }
      )
      .iterator
  }

  // Names of methods that are autogenerated by the Scala compiler.
  private val isSyntheticMethodName = Set(
    "productElement", "productElementName", "equals", "canEqual", "hashCode",
    "productIterator", "toString", "productPrefix", "unapply", "apply", "copy",
    "productArity", "readResolve"
  )

  private def isRelevant(info: SymbolInformation): Boolean =
    !info.isPrivate &&
      !info.isPrivateThis && {
        info.kind match {
          case k.METHOD =>
            val isVarSetter = info.isVar && info.displayName.endsWith("_=")
            !isVarSetter &&
            !isSyntheticMethodName(info.displayName) &&
            !info.displayName.contains("$default$")
          case k.CLASS | k.INTERFACE | k.OBJECT | k.PACKAGE_OBJECT | k.TRAIT |
              k.MACRO | k.FIELD =>
            true
          case _ => false
        }
      }

  private def loadSymbols(
      in: AbsolutePath,
      symbol: String
  ): Array[TreeViewSymbolInformation] = {
    val index = ClasspathIndex(Classpath(in), false)
    val buf = Array.newBuilder[TreeViewSymbolInformation]
    def list(root: AbsolutePath): Unit = {
      val dir =
        if (
          symbol == Scala.Symbols.RootPackage || symbol == Scala.Symbols.EmptyPackage
        ) root
        else root.resolve(Symbol(symbol).enclosingPackage.value)

      dir.list.foreach {
        case path if path.isDirectory =>
          buf ++= dummyClassfiles(root.toNIO, path.toNIO)

        case path if isClassfile(path.toNIO) =>
          try {
            val node = path.toClassNode
            classfileSymbols(
              path.toNIO,
              node,
              index,
              { i =>
                if (isRelevant(i)) {
                  buf += TreeViewSymbolInformation(
                    i.symbol,
                    i.kind,
                    i.properties
                  )
                }
              }
            )
          } catch {
            case NonFatal(ex) =>
              scribe.warn(s"error: can't convert $path in $in", ex)
          }

        case _ =>
      }

    }
    if (in.extension == "jar") {
      FileIO.withJarFileSystem(in, create = false, close = true)(list)
    } else {
      list(in)
    }
    buf.result()
  }

  /**
   * Recursively look for classfiles in this directory to determine if this
   * path maps to a non-empty package symbol.
   */
  private def dummyClassfiles(
      root: Path,
      path: Path
  ): Seq[TreeViewSymbolInformation] = {
    val result = mutable.ListBuffer.empty[TreeViewSymbolInformation]
    def isDone = result.lengthCompare(1) > 0
    Files.walkFileTree(
      path,
      new SimpleFileVisitor[Path] {
        override def preVisitDirectory(
            dir: Path,
            attrs: BasicFileAttributes
        ): FileVisitResult =
          if (isDone) FileVisitResult.SKIP_SIBLINGS
          else FileVisitResult.CONTINUE
        override def visitFile(
            child: Path,
            attrs: BasicFileAttributes
        ): FileVisitResult = {
          if (isDone) FileVisitResult.CONTINUE
          else {
            if (isClassfile(child) && !child.filename.contains("$")) {
              val relpath = root.relativize(child).iterator().asScala
              val dummySymbol = relpath.foldLeft(Symbols.RootPackage) {
                case (owner, path) =>
                  Symbols.Global(
                    owner,
                    d.Package(path.filename.stripSuffix("/"))
                  )
              }
              result += TreeViewSymbolInformation(dummySymbol, k.CLASS, 0)
            }
            FileVisitResult.CONTINUE
          }

        }
      }
    )
    result.toSeq
  }

  private def isClassfile(path: Path): Boolean = {
    PathIO.extension(path) == "class" && Files.size(path) > 0
  }

  private def classfileSymbols(
      path: Path,
      node: ClassNode,
      index: ClasspathIndex,
      fn: SymbolInformation => Unit
  ): Unit = {
    node.scalaSig match {
      case Some(scalaSig) =>
        val ops = new ScalacpCopyPaste(scalaSig)
        import ops._
        def infos(sym: SymbolInfoSymbol): List[SymbolInformation] = {
          if (sym.isSemanticdbLocal) return Nil
          if (sym.isUseless) return Nil
          val ssym = sym.ssym
          if (ssym.contains("$extension")) return Nil
          val sinfo = sym.toSymbolInformation(SymlinkChildren)
          if (sym.isUsefulField && sym.isMutable) {
            List(sinfo) ++ Synthetics.setterInfos(sinfo, SymlinkChildren)
          } else {
            List(sinfo)
          }
        }
        scalaSig.scalaSig.symbols.foreach {
          case sym: SymbolInfoSymbol if !sym.isSynthetic =>
            infos(sym).foreach(fn)
          case _ =>
        }
      case None =>
        val attrs =
          if (node.attrs != null) node.attrs.asScala else Nil
        if (attrs.exists(_.`type` == "Scala")) {
          None
        } else {
          val innerClassNode =
            node.innerClasses.asScala.find(_.name == node.name)
          if (innerClassNode.isEmpty && node.name != "module-info") {
            Javacp.parse(node, index).infos.foreach(fn)
          }
        }
    }
  }

}
