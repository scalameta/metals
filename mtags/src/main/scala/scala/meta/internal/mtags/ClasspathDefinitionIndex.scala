package scala.meta.internal.mtags

import java.util.HashSet
import java.util.jar.JarFile

import scala.collection.concurrent.TrieMap
import scala.util.control.NonFatal

import scala.meta.dialects.Scala213
import scala.meta.internal.io.FileIO
import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.io.AbsolutePath

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.slf4j.LoggerFactory

/**
 * An index for "Go to definition" requests that requires minimal upfront
 * indexing or memory overhead.
 *
 * - At indexing time, this class only records what  packages are declared by
 *   each jar. For example, scala-library.jar declares the packages `scala/`,
 *   `scala/util/`, `scala/collection/`, etc.
 * - At query time, this class uses the index to identify the jars that may
 *   contain the requested symbol, reads the "source" debug attribute of the
 *   accompanying classfile, and then does a brute force search against all files
 *   have have a matching names in the accompanying *-sources.jar.
 */
class ClasspathDefinitionIndex(mtags: Mtags) {
  private val declaredPackagesByJar =
    TrieMap.empty[DependencyModule, collection.Set[String]]
  private val logger = LoggerFactory.getLogger(getClass)
  def reset(): Unit = {
    declaredPackagesByJar.clear()
  }

  def definitions(
      symbol: Symbol
  ): List[(DependencyModule, SymbolDefinition)] = {
    val toplevel = symbol.enclosingPackage
    for {
      (module, packages) <- declaredPackagesByJar.iterator
      if packages.contains(toplevel.value)
      filename <- classfileSourceName(module, symbol).toList
      // Iterate through *all* files with a matching name, ignoring the package
      // name.  If a file has 100s of "package.scala" files, then we index them
      // all here, which could be inefficient and might be worth optimizing in
      // the future. Ideally, we should find the first file that has a match,
      // and we can intelligently guess that the file is most likely named
      // "PACKAGE/FILENAME".
      source <- moduleSources(module, filename)
      doc = mtags.allSymbols(source, Scala213)
      occ <- findBestDefinition(symbol, doc)
      info = doc.symbols.find(_.symbol == occ.symbol)
      kind = info.map(_.kind)
      props = info.fold(0)(_.properties)
    } yield (
      module,
      SymbolDefinition(
        querySymbol = symbol,
        definitionSymbol = Symbol(occ.symbol),
        source,
        Scala213,
        occ.range,
        kind,
        props
      )
    )
  }.toList

  // Returns the SymbolOccurrence that defines the given symbol. In the basic
  // case, these are the same symbols.  In trickier cases, we're trying to find
  // the definition of a generated symbol like the `copy()` parameter name of a
  // case class, which navigates to the case class field.
  // Example: `case class Something(value: Int)` has a generated `def
  // copy(value: Int = this.value)`, and the symbol of the `value` parameter
  // should navigate to the `value` field.
  private def findBestDefinition(
      symbol: Symbol,
      doc: TextDocument
  ): Option[SymbolOccurrence] = {
    val candidates = (symbol :: DefinitionAlternatives(symbol))
    val candidateRank = candidates.iterator.zipWithIndex.toMap
    doc.occurrences
      .flatMap(occ =>
        candidateRank.get(Symbol(occ.symbol)).toList.map(rank => occ -> rank)
      )
      .sortBy { case (_, rank) => rank }
      .map { case (occ, _) => occ }
      .headOption
  }

  private def moduleSources(
      module: DependencyModule,
      filename: String
  ): Seq[AbsolutePath] = {
    module.sources match {
      case None => Seq.empty
      case Some(sources) =>
        FileIO.withJarFileSystem(sources, create = false) { root =>
          FileIO
            .listAllFilesRecursively(root)
            .filter(_.toNIO.endsWith(filename))
            .toSeq
        }
    }
  }
  private def classfileSourceName(
      module: DependencyModule,
      symbol: Symbol
  ): Option[String] = {
    val jar = new JarFile(module.jar.toFile)
    val classfileName =
      s"${symbol.toplevel.value.stripSuffix("#").stripSuffix(".")}.class"
    try {
      val entry = jar.getEntry(classfileName)
      if (entry != null) {
        val in = jar.getInputStream(entry)
        val visitor = new ClassfileSourceExtractor()
        new ClassReader(in).accept(
          visitor,
          ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES
        )
        visitor.source
      } else {
        None
      }
    } finally {
      jar.close()
    }
  }

  def addDependencyModule(dependencyModule: DependencyModule): Unit = {
    try {
      val classNames = new HashSet[String]().asScala
      val jar = new JarFile(dependencyModule.jar.toFile)
      try {
        val entries = jar.entries()
        while (entries.hasMoreElements) {
          val entry = entries.nextElement()
          if (
            !entry.getName.startsWith("META-INF/") &&
            entry.getName().endsWith(".class")
          ) {
            val slash = entry.getName().lastIndexOf('/')
            if (slash > 0) {
              classNames += entry.getName().substring(0, slash + 1)
            }
          }
        }
        if (classNames.nonEmpty) {
          declaredPackagesByJar.put(dependencyModule, classNames)
        }
      } finally {
        jar.close()
      }
    } catch {
      case NonFatal(e) =>
        logger.error(
          s"Error adding dependency module ${dependencyModule.jar}",
          e
        )
    }
  }

  /** Minimal ASM classfile visitor that only extracts the name of the source file from the classfile */
  private final class ClassfileSourceExtractor
      extends ClassVisitor(Opcodes.ASM9) {
    var source: Option[String] = None
    override def visitSource(source: String, debug: String): Unit = {
      this.source = Some(source)
    }
    override def visitField(
        access: Int,
        name: String,
        desc: String,
        signature: String,
        value: Any
    ): FieldVisitor = {
      return null
    }
    override def visitMethod(
        access: Int,
        name: String,
        desc: String,
        signature: String,
        exceptions: Array[String]
    ): MethodVisitor = {
      return null
    }
  }
}
