package scala.meta.internal.metals.testProvider.frameworks

import scala.reflect.NameTransformer

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.testProvider.TestCaseEntry
import scala.meta.internal.mtags
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.semanticdb.TypeRef
import scala.meta.io.AbsolutePath

class JunitTestFinder {
  import JunitTestFinder._

  def findTests(
      doc: TextDocument,
      path: AbsolutePath,
      suiteSymbol: mtags.Symbol,
  ): Vector[TestCaseEntry] = {
    val uri = path.toURI

    def isMethodWithTestAnnotation(symbol: SymbolInformation) =
      symbol.kind == SymbolInformation.Kind.METHOD && symbol.annotations
        .exists(_.tpe match {
          case TypeRef(_, annotationSymbol, _) =>
            junitAnnotationSymbols.contains(annotationSymbol)
          case _ => false
        })

    def isValid(symbol: SymbolInformation): Boolean = {
      isMethodWithTestAnnotation(symbol) && symbol.symbol.startsWith(
        suiteSymbol.value
      )
    }

    doc.symbols
      .collect {
        case symbol if isValid(symbol) =>
          doc
            .toLocation(uri, symbol.symbol)
            .map { location =>
              val encodedName = NameTransformer.encode(symbol.displayName)
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

object JunitTestFinder {
  val junit4AnnotationSymbol = "org/junit/Test#"
  val junit5AnnotationSymbol = "org/junit/jupiter/api/Test#"
  val junitAnnotationSymbols: Set[String] =
    Set(junit4AnnotationSymbol, junit5AnnotationSymbol)
}
