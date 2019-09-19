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
import scala.meta.internal.semanticdb.Signature
import scala.meta.internal.semanticdb.TextDocument
import java.util.concurrent.ConcurrentHashMap

final class ImplementationProvider(
    semanticdbs: Semanticdbs,
    workspace: AbsolutePath,
    definitionProvider: DefinitionProvider
) {
  private val implementations =
    new ConcurrentHashMap[String, Set[ClassLocation]]

  def clear(): Unit = {
    implementations.clear()
  }

  def implementations(params: TextDocumentPositionParams): List[Location] = {
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
      impl <- findImplementation(occ.symbol)
      range <- impl.symbol.range
      revised <- positionOccurrence.distance.toRevised(range.toLSP)
      path = workspace.toNIO.resolve(Paths.get(impl.uri))
      uri = path.toUri.toString
    } yield new Location(uri, revised)
  }

  def onChange(docs: TextDocuments): Unit = {
    docs.documents.foreach { doc =>
      doc.symbols.foreach { thisSymbol =>
        doc.occurrences
          .find(
            occ => occ.symbol == thisSymbol.symbol && occ.role.isDefinition
          )
          .foreach(occ => addFromSignature(thisSymbol.signature, occ, doc))
      }
    }
  }

  private def findImplementation(symbol: String): Set[ClassLocation] = {
    def findAllImpl(symbol: String): Set[ClassLocation] = {
      val directImpl = implementations.getOrDefault(symbol, Set.empty)
      directImpl ++ directImpl
        .flatMap(
          loc => findAllImpl(loc.symbol.symbol)
        )
    }
    findAllImpl(symbol)
  }

  private def addFromSignature(
      signature: Signature,
      occ: SymbolOccurrence,
      doc: TextDocument
  ): Unit = {
    signature match {
      case classSig: ClassSignature =>
        classSig.parents.collect {
          case TypeRef(_, symbol, _) =>
            val loc = ClassLocation(occ, doc.uri)
            implementations.compute(symbol, { (_, set) =>
              if (set == null) Set(loc)
              else set + loc
            })
        }
      case _ =>
    }
  }

  private case class ClassLocation(symbol: SymbolOccurrence, uri: String)
}
