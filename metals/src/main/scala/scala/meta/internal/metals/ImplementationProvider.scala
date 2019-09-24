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
import java.nio.file.Path

final class ImplementationProvider(
    semanticdbs: Semanticdbs,
    workspace: AbsolutePath,
    buffer: Buffers,
    definitionProvider: DefinitionProvider
) {
  private val implementationsInPath =
    new ConcurrentHashMap[Path, Map[String, Set[ClassLocation]]]

  def implementations(params: TextDocumentPositionParams): List[Location] = {
    val source = params.getTextDocument.getUri.toAbsolutePath

    def findSemanticdb(fileSource: AbsolutePath) =
      semanticdbs
        .textDocument(fileSource)
        .documentIncludingStale
        .toList

    for {
      currentDoc <- findSemanticdb(source)
      positionOccurrence = definitionProvider.positionOccurrence(
        source,
        params,
        currentDoc
      )
      occ <- positionOccurrence.occurrence.toList
      (file, locations) <- findImplementation(occ.symbol).groupBy(_.file)
      fileSource = AbsolutePath(file)
      doc <- findSemanticdb(fileSource)
      distance = TokenEditDistance.fromBuffer(fileSource, doc.text, buffer)
      impl <- locations
      implOccurence <- impl.definition(doc)
      range <- implOccurence.range
      revised <- distance.toRevised(range.toLSP)
      uri = impl.file.toUri.toString
    } yield new Location(uri, revised)
  }

  private def findImplementation(symbol: String): Set[ClassLocation] = {
    val directImpl = for {
      (_, symbols) <- implementationsInPath.asScala
      symbolImpls <- symbols.get(symbol).toList
      impl <- symbolImpls
    } yield impl
    directImpl.toSet ++ directImpl
      .flatMap(
        loc => findImplementation(loc.symbol)
      )
  }

  def clear(): Unit = {
    implementationsInPath.clear()
  }

  def onDelete(path: Path): Unit = {
    implementationsInPath.remove(path)
  }

  def onChange(docs: TextDocuments, path: Path): Unit = {
    implementationsInPath.compute(
      path, { (_, _) =>
        computeInheritance(docs)
      }
    )
  }

  private def computeInheritance(
      docs: TextDocuments
  ): Map[String, Set[ClassLocation]] = {
    val allParents = for {
      doc <- docs.documents
      thisSymbol <- doc.symbols
      parent <- parentsFromSignature(
        thisSymbol.symbol,
        thisSymbol.signature,
        doc
      ).toList
    } yield parent

    allParents.groupBy(_._1).map {
      case (symbol, locations) =>
        symbol -> locations.map(_._2).toSet
    }
  }

  private def parentsFromSignature(
      symbol: String,
      signature: Signature,
      doc: TextDocument
  ): Seq[(String, ClassLocation)] = {
    val filePath = workspace.toNIO.resolve(Paths.get(doc.uri))
    val loc = ClassLocation(symbol, filePath)
    signature match {
      case classSig: ClassSignature =>
        val allLocations = classSig.parents.collect {
          case t: TypeRef =>
            t.symbol -> loc
        }
        allLocations
      case _ =>
        Seq.empty
    }
  }

  private case class ClassLocation(symbol: String, file: Path) {
    private var definitionOccurence: Option[SymbolOccurrence] = None

    def definition(semanticDb: TextDocument): Option[SymbolOccurrence] =
      synchronized {
        definitionOccurence.orElse {
          val foundOcc = semanticDb.occurrences.find(
            occ => occ.role.isDefinition && occ.symbol == symbol
          )
          definitionOccurence = foundOcc
          foundOcc
        }
      }
  }
}
