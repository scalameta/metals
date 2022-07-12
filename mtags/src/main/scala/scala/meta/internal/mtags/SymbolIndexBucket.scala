package scala.meta.internal.mtags

import java.io.UncheckedIOException
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets

import scala.collection.concurrent.TrieMap
import scala.util.Properties

import scala.meta.Dialect
import scala.meta.inputs.Input
import scala.meta.internal.io.FileIO
import scala.meta.internal.io.PathIO
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.mtags.SymbolIndexBucket.loadFromSourceJars
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.{semanticdb => s}
import scala.meta.io.AbsolutePath

final case class SymbolLocation(
    path: AbsolutePath,
    range: Option[s.Range]
)

/**
 * Index split on buckets per dialect in order to have a constant time
 * and low memory footprint to infer dialect for SymbolDefinition because
 * it's used in WorkspaceSymbolProvider
 *
 * @param toplevels keys are non-trivial toplevel symbols and values are the file
 *                  the symbols are defined in.
 * @param definitions keys are global symbols and the values are the files the symbols
 *                    are defined in. Difference between toplevels and definitions
 *                    is that toplevels contains only symbols generated by ScalaToplevelMtags
 *                    while definitions contains only symbols generated by ScalaMtags.
 */
class SymbolIndexBucket(
    toplevels: TrieMap[String, Set[AbsolutePath]],
    definitions: TrieMap[String, Set[SymbolLocation]],
    sourceJars: ClasspathLoader,
    toIndexSource: AbsolutePath => AbsolutePath = identity,
    mtags: Mtags,
    dialect: Dialect
) {

  def close(): Unit = sourceJars.close()

  def addSourceDirectory(dir: AbsolutePath): List[(String, AbsolutePath)] = {
    if (sourceJars.addEntry(dir)) {
      dir.listRecursive.toList.flatMap {
        case source if source.isScala =>
          addSourceFile(source, Some(dir)).map(sym => (sym, source))
        case _ =>
          List.empty
      }
    } else
      List.empty
  }

  def addSourceJar(jar: AbsolutePath): List[(String, AbsolutePath)] = {
    if (sourceJars.addEntry(jar)) {
      FileIO.withJarFileSystem(jar, create = false) { root =>
        try {
          root.listRecursive.toList.flatMap {
            case source if source.isScala || source.isJava =>
              addSourceFile(source, None).map(sym => (sym, source))
            case _ =>
              List.empty
          }
        } catch {
          // this happens in broken jars since file from FileWalker should exists
          case _: UncheckedIOException => Nil
        }
      }
    } else
      List.empty
  }

  def addIndexedSourceJar(
      jar: AbsolutePath,
      symbols: List[(String, AbsolutePath)]
  ): Unit = {
    if (sourceJars.addEntry(jar)) {
      symbols.foreach { case (sym, path) =>
        val acc = toplevels.getOrElse(sym, Set.empty)
        toplevels(sym) = acc + path
      }
    }
  }

  def addSourceFile(
      source: AbsolutePath,
      sourceDirectory: Option[AbsolutePath]
  ): List[String] = {
    val uri = source.toIdeallyRelativeURI(sourceDirectory)
    val symbols = indexSource(source, uri, dialect)
    symbols.foreach { symbol =>
      val acc = toplevels.getOrElse(symbol, Set.empty)
      toplevels(symbol) = acc + source
    }
    symbols
  }

  private def indexSource(
      source: AbsolutePath,
      uri: String,
      dialect: Dialect
  ): List[String] = {
    val text = FileIO.slurp(source, StandardCharsets.UTF_8)
    val input = Input.VirtualFile(uri, text)
    val sourceToplevels = mtags.toplevels(input, dialect)
    if (source.isAmmoniteScript)
      sourceToplevels
    else
      sourceToplevels.filter(sym => !isTrivialToplevelSymbol(uri, sym))
  }

  // Returns true if symbol is com/foo/Bar# and path is /com/foo/Bar.scala
  // Such symbols are "trivial" because their definition location can be computed
  // on the fly.
  private def isTrivialToplevelSymbol(path: String, symbol: String): Boolean = {
    val pathBuffer =
      CharBuffer.wrap(path).subSequence(1, path.length - ".scala".length)
    val symbolBuffer =
      CharBuffer.wrap(symbol).subSequence(0, symbol.length - 1)
    pathBuffer.equals(symbolBuffer)
  }

  def addToplevelSymbol(
      path: String,
      source: AbsolutePath,
      toplevel: String
  ): Unit = {
    if (source.isAmmoniteScript || !isTrivialToplevelSymbol(path, toplevel)) {
      val acc = toplevels.getOrElse(toplevel, Set.empty)
      toplevels(toplevel) = acc + source
    }
  }

  def query(symbol: Symbol): List[SymbolDefinition] =
    query0(symbol, symbol)

  /**
   * Returns the file where symbol is defined, if any.
   *
   * Uses two strategies to recover from missing symbol definitions:
   * - try to enter the toplevel symbol definition, then lookup symbol again.
   * - if the symbol is synthetic, for examples from a case class of macro annotation,
   *  fall back to related symbols from the enclosing class, see `DefinitionAlternatives`.
   *
   * @param querySymbol The original symbol that was queried by the user.
   * @param symbol The symbol that
   * @return
   */
  private def query0(
      querySymbol: Symbol,
      symbol: Symbol
  ): List[SymbolDefinition] = {
    if (!definitions.contains(symbol.value)) {
      // Fallback 1: enter the toplevel symbol definition
      val toplevel = symbol.toplevel
      toplevels.get(toplevel.value) match {
        case Some(files) =>
          files.foreach(addMtagsSourceFile)
        case _ =>
          loadFromSourceJars(toplevel.value)
            .foreach(_.foreach(addMtagsSourceFile))

      }
    }
    if (!definitions.contains(symbol.value)) {
      // Fallback 2: guess related symbols from the enclosing class.
      DefinitionAlternatives(symbol)
        .flatMap { alternative =>
          query0(querySymbol, alternative)
        }
    } else {
      definitions
        .get(symbol.value)
        .map { paths =>
          paths.map { location =>
            SymbolDefinition(
              querySymbol = querySymbol,
              definitionSymbol = symbol,
              path = location.path,
              dialect = dialect,
              range = location.range
            )
          }.toList
        }
        .getOrElse(List.empty)
    }
  }
  // similar as addSourceFile except indexes all global symbols instead of
  // only non-trivial toplevel symbols.
  private def addMtagsSourceFile(file: AbsolutePath): Unit = {
    val docs: s.TextDocuments = PathIO.extension(file.toNIO) match {
      case "scala" | "java" | "sc" =>
        val language = file.toLanguage
        val toIndexSource0 = toIndexSource(file)
        val input = toIndexSource0.toInput
        val document = mtags.index(language, input, dialect)
        s.TextDocuments(List(document))
      case _ =>
        s.TextDocuments(Nil)
    }
    if (docs.documents.nonEmpty)
      addTextDocuments(file, docs)
  }

  // Records all global symbol definitions.
  private def addTextDocuments(
      file: AbsolutePath,
      docs: s.TextDocuments
  ): Unit = {

    docs.documents.foreach { document =>
      document.occurrences.foreach { occ =>
        if (occ.symbol.isGlobal && occ.role.isDefinition) {
          val acc = definitions.getOrElse(occ.symbol, Set.empty)

          definitions.put(occ.symbol, acc + SymbolLocation(file, occ.range))
        } else {
          // do nothing, we only care about global symbol definitions.
        }
      }
    }
  }

}

object SymbolIndexBucket {

  val sourceJars = new ClasspathLoader()

  def empty(
      dialect: Dialect,
      mtags: Mtags,
      toIndexSource: AbsolutePath => AbsolutePath
  ): SymbolIndexBucket =
    new SymbolIndexBucket(
      TrieMap.empty,
      TrieMap.empty,
      sourceJars,
      toIndexSource,
      mtags,
      dialect
    )

  def loadFromSourceJars(symbolValue: String): Option[List[AbsolutePath]] = {
    loadFromSourceJars(trivialPaths(symbolValue))
      .orElse(loadFromSourceJars(modulePaths(symbolValue)))
  }

  // Returns the first path that resolves to a file.
  private def loadFromSourceJars(
      paths: List[String]
  ): Option[List[AbsolutePath]] = {
    paths match {
      case Nil => None
      case head :: tail =>
        sourceJars.loadAll(head) match {
          case Nil => loadFromSourceJars(tail)
          case values => Some(values)
        }
    }
  }

  // Returns relative file paths for trivial toplevel symbols, example:
  // Input:  scala/collection/immutable/List#
  // Output: scala/collection/immutable/List.scala
  //         scala/collection/immutable/List.java
  private def trivialPaths(toplevelValue: String): List[String] = {
    val noExtension = toplevelValue.stripSuffix(".").stripSuffix("#")
    List(
      noExtension + ".scala",
      noExtension + ".java"
    )
  }

  private def modulePaths(toplevelValue: String): List[String] = {
    if (Properties.isJavaAtLeast("9")) {
      val noExtension = toplevelValue.stripSuffix(".").stripSuffix("#")
      val javaSymbol = noExtension.replace("/", ".")
      for {
        cls <- sourceJars.loadClass(javaSymbol).toList
        // note(@tgodzik) Modules are only available in Java 9+, so we need to invoke this reflectively
        module <- Option(
          cls.getClass().getMethod("getModule").invoke(cls)
        ).toList
        moduleName <- Option(
          module.getClass().getMethod("getName").invoke(module)
        ).toList
        file <- List(
          s"$moduleName/$noExtension.java",
          s"$moduleName/$noExtension.scala"
        )
      } yield file
    } else {
      Nil
    }
  }
}
