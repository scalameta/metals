package scala.meta.internal.metals
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.TextDocumentPositionParams
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath
import java.nio.file.Paths
import scala.meta.internal.semanticdb.TextDocuments
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.semanticdb.ClassSignature
import scala.meta.internal.semanticdb.TypeRef
import scala.collection.concurrent.TrieMap

final class ImplementationProvider(
    semanticdbs: Semanticdbs,
    workspace: AbsolutePath,
    definitionProvider: DefinitionProvider
) {
  private val implementations: TrieMap[String, Set[ClassLocation]] =
    TrieMap.empty

  def reset(): Unit = {
    implementations.clear()
  }

  def implementation(params: TextDocumentPositionParams): List[Location] = {
    val source = params.getTextDocument.getUri.toAbsolutePath
    val result = semanticdbs.textDocument(source)
    for {
      doc <- result.documentIncludingStale.toList
      positionOccurrence = definitionProvider.positionOccurrence(
        source,
        params,
        doc
      )
      occ <- positionOccurrence.occurrence.toList
      defn <- findImplementation(occ.symbol)
      range <- defn.symbol.range
      revised <- positionOccurrence.distance.toRevised(range.toLSP)
      path = workspace.toNIO.resolve(Paths.get(defn.uri))
      uri = path.toUri.toString
    } yield new Location(uri, revised)
  }

  private def findImplementation(symbol: String): Set[ClassLocation] = {
    def findAllImpl(symbol: String): Set[ClassLocation] = {
      implementations.get(symbol) match {
        case None => Set.empty
        case Some(set) =>
          set ++ set
            .flatMap(
              loc => findAllImpl(loc.symbol.symbol)
            )
      }
    }
    findAllImpl(symbol)
  }

  def onChange(docs: TextDocuments): Unit = {
    docs.documents.foreach { doc =>
      doc.symbols.foreach { thisSymbol =>
        thisSymbol.signature match {
          case ClassSignature(typeParameters, parents, self, declarations) =>
            parents.collect {
              case TypeRef(_, symbol, _) =>
                doc.occurrences
                  .find(
                    occ =>
                      occ.symbol == thisSymbol.symbol && occ.role.isDefinition
                  )
                  .foreach { occ =>
                    val children =
                      implementations.getOrElse(symbol, Set.empty)
                    implementations.put(
                      symbol,
                      children + ClassLocation(occ, doc.uri)
                    )
                  }
            }
          case _ =>
        }
      }
    }
  }

  case class ClassLocation(symbol: SymbolOccurrence, uri: String)
}
