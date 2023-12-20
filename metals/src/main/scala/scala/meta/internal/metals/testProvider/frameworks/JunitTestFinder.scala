package scala.meta.internal.metals.testProvider.frameworks

import scala.reflect.NameTransformer

import scala.meta.internal.metals.MetalsEnrichments.given
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
            annotationSymbol == junitAnnotationSymbol
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
  val junitAnnotationSymbol = "org/junit/Test#"
}
