package tests

import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.JavaInteractiveSemanticdb
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.SemanticdbClasspath
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.io.AbsolutePath

/**
 * Base class for all expect tests.
 *
 * Exposes useful methods to lookup metadata about the input project.
 */
abstract class BaseExpectSuite(val suiteName: String) extends BaseSuite {
  lazy val input: InputProperties = InputProperties.scala2()
  lazy val javaInteractiveSemanticdb: JavaInteractiveSemanticdb =
    JavaInteractiveSemanticdb
      .create(
        sourceroot,
        BuildTargets.empty,
      )
  case class SymbolTable(symbols: Seq[SymbolInformation]) {
    def info(sym: String): Option[SymbolInformation] = {
      symbols.find(_.symbol == sym)
    }
  }
  def symtab(file: AbsolutePath): SymbolTable = {
    if (file.isScala)
      classpath.textDocument(file).documentIncludingStale match {
        case None =>
          throw new IllegalArgumentException(s"no semanticdb for $file")
        case Some(textDoc) =>
          SymbolTable(textDoc.symbols)
      }
    else if (file.isJava) {
      val textDoc = javaInteractiveSemanticdb.textDocument(file, file.readText)
      SymbolTable(textDoc.symbols)
    } else throw new RuntimeException(s"unsupported file extension: $file")
  }
  final lazy val sourceroot: AbsolutePath =
    AbsolutePath(BuildInfo.sourceroot)
  final lazy val classpath: SemanticdbClasspath = {
    new SemanticdbClasspath(
      sourceroot,
      input.classpath,
      input.semanticdbTargets,
    )
  }
  def saveExpect(): Unit
}
