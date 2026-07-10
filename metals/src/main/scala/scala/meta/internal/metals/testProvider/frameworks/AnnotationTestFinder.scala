package scala.meta.internal.metals.testProvider.frameworks

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.testProvider.TestCaseEntry
import scala.meta.internal.mtags
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.semanticdb.TypeRef
import scala.meta.io.AbsolutePath

trait AnnotationTestFinder {
  def expectedAnnotationSymbols: Set[String]

  def encode(annotationSymbol: String, displayName: String): String

  def findTests(
      doc: TextDocument,
      path: AbsolutePath,
      suiteSymbol: mtags.Symbol,
  ): Vector[TestCaseEntry] = {
    val uri = path.toURI

    def isMethodWithTestAnnotation(
        symbol: SymbolInformation
    ): Option[String] = {
      if (symbol.kind != SymbolInformation.Kind.METHOD) None
      else
        symbol.annotations
          .flatMap(_.tpe match {
            case TypeRef(_, annotationSymbol, _)
                if expectedAnnotationSymbols.contains(annotationSymbol) =>
              Some(annotationSymbol)
            case _ => None
          })
          .headOption
    }

    def validAnnotation(symbol: SymbolInformation): Option[String] =
      if (symbol.symbol.startsWith(suiteSymbol.value))
        isMethodWithTestAnnotation(symbol)
      else None

    doc.symbols
      .map(symbol => validAnnotation(symbol).map(symbol -> _))
      .collect { case Some((symbol, annotSymbol)) =>
        doc
          .toLocation(uri, symbol.symbol)
          .map { location =>
            val encodedName = encode(annotSymbol, symbol.displayName)
            TestCaseEntry(
              encodedName,
              symbol.displayName,
              location,
            )
          }
      }
      .flatten
      .toVector
  }
}
