package scala.meta.internal.mtags

import java.util.zip.ZipError

import scala.collection.concurrent.TrieMap

import scala.meta.Dialect
import scala.meta.internal.io.{ListFiles => _}
import scala.meta.io.AbsolutePath

/**
 * An implementation of GlobalSymbolIndex with fast indexing and low memory usage.
 *
 * Fast indexing is enabled by ScalaToplevelMtags, a custom parser that extracts
 * only toplevel symbols from a Scala source file. Java source files don't need indexing
 * because their file location can be inferred from the symbol with the limitation
 * that it doesn't work for Java source files with multiple package-private top-level classes.
 *
 * Low memory usage is enabled by only storing "non-trivial toplevel" symbols.
 * A symbol is "toplevel" when its owner is a package. A symbol is "non-trivial"
 * when it doesn't match the path of the file it's defined in, for example `Some#`
 * in Option.scala is non-trivial while `Option#` in Option.scala is trivial.
 */
final class OnDemandSymbolIndex(
    dialectBuckets: TrieMap[Dialect, SymbolIndexBucket],
    onError: PartialFunction[Throwable, Unit],
    toIndexSource: AbsolutePath => Option[AbsolutePath]
) extends GlobalSymbolIndex {
  val mtags = new Mtags
  var indexedSources = 0L
  def close(): Unit = {
    dialectBuckets.values.foreach(_.close())
  }
  private val onErrorOption = onError.andThen(_ => None)
  private def getOrCreateBucket(dialect: Dialect): SymbolIndexBucket = {
    dialectBuckets.getOrElseUpdate(
      dialect,
      SymbolIndexBucket.empty(dialect, mtags, toIndexSource)
    )
  }

  override def definition(symbol: Symbol): Option[SymbolDefinition] = {
    try findSymbolDefinition(symbol).headOption
    catch onErrorOption
  }

  override def definitions(symbol: Symbol): List[SymbolDefinition] =
    try findSymbolDefinition(symbol)
    catch onError.andThen(_ => List.empty)

  override def addSourceDirectory(
      dir: AbsolutePath,
      dialect: Dialect
  ): List[(String, AbsolutePath)] =
    tryRun(
      List.empty,
      getOrCreateBucket(dialect).addSourceDirectory(dir)
    )

  // Traverses all source files in the given jar file and records
  // all non-trivial toplevel Scala symbols.
  override def addSourceJar(
      jar: AbsolutePath,
      dialect: Dialect
  ): List[(String, AbsolutePath)] =
    tryRun(
      List.empty, {
        try {
          getOrCreateBucket(dialect).addSourceJar(jar)
        } catch {
          case e: ZipError =>
            onError(InvalidJarException(jar, e))
            List.empty
        }
      }
    )

  // Used to add cached toplevel symbols to index
  def addIndexedSourceJar(
      jar: AbsolutePath,
      symbols: List[(String, AbsolutePath)],
      dialect: Dialect
  ): Unit = {
    getOrCreateBucket(dialect).addIndexedSourceJar(jar, symbols)
  }

  // Enters nontrivial toplevel symbols for Scala source files.
  // All other symbols can be inferred on the fly.
  override def addSourceFile(
      source: AbsolutePath,
      sourceDirectory: Option[AbsolutePath],
      dialect: Dialect
  ): List[String] =
    tryRun(
      List.empty, {
        indexedSources += 1
        getOrCreateBucket(dialect).addSourceFile(source, sourceDirectory)
      }
    )

  def addToplevelSymbol(
      path: String,
      source: AbsolutePath,
      toplevel: String,
      dialect: Dialect
  ): Unit =
    getOrCreateBucket(dialect).addToplevelSymbol(path, source, toplevel)

  private def tryRun[A](fallback: => A, thunk: => A): A =
    try thunk
    catch onError.andThen(_ => fallback)

  private def findSymbolDefinition(
      querySymbol: Symbol
  ): List[SymbolDefinition] = {
    dialectBuckets.values.toList.flatMap(_.query(querySymbol))
  }

}

object OnDemandSymbolIndex {
  def empty(
      onError: PartialFunction[Throwable, Unit] = PartialFunction.empty,
      toIndexSource: AbsolutePath => Option[AbsolutePath] = _ => None
  ): OnDemandSymbolIndex =
    new OnDemandSymbolIndex(TrieMap.empty, onError, toIndexSource)
}
