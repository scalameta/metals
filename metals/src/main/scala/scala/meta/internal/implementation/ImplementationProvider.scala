package scala.meta.internal.implementation

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.TextDocumentPositionParams
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.mtags.{Symbol => MSymbol}
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
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.internal.semanticdb.MethodSignature
import scala.meta.internal.mtags.GlobalSymbolIndex
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.DefinitionProvider
import scala.meta.internal.metals.TokenEditDistance

final class ImplementationProvider(
    semanticdbs: Semanticdbs,
    workspace: AbsolutePath,
    index: GlobalSymbolIndex,
    buildTargets: BuildTargets,
    buffer: Buffers,
    definitionProvider: DefinitionProvider
) {
  import ImplementationProvider._

  private val globalTable = new GlobalClassTable(buildTargets)
  private val implementationsInPath =
    new ConcurrentHashMap[Path, Map[String, Set[ClassLocation]]]

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

  def implementations(params: TextDocumentPositionParams): List[Location] = {
    val source = params.getTextDocument.getUri.toAbsolutePath

    val Some(symTab) = globalTable.classesFor(source)

    def findSemanticdb(fileSource: AbsolutePath) =
      semanticdbs
        .textDocument(fileSource)
        .documentIncludingStale
        .toList

    def findSemanticDbForSymbol(symbol: String): List[TextDocument] = {
      for {
        symbolDefinition <- index.definition(MSymbol(symbol)).toList
        document <- findSemanticdb(symbolDefinition.path)
      } yield {
        document
      }
    }

    for {
      currentDoc <- findSemanticdb(source)
      positionOccurrence = definitionProvider.positionOccurrence(
        source,
        params,
        currentDoc
      )
      occ <- positionOccurrence.occurrence.toList
      _ = pprint.log(occ.symbol)
      _ = pprint.log(symTab.info(occ.symbol))
      definitionDocument <- if (currentDoc.definesSymbol(occ.symbol))
        List(currentDoc)
      else findSemanticDbForSymbol(occ.symbol).toList
      plainSym <- findSymbol(definitionDocument, occ.symbol).toList
      sym = plainSym.copy(
        signature = enrichSignature(plainSym.signature, definitionDocument)
      )
      classSym <- classFromSymbol(sym, definitionDocument).toList
      (file, locations) <- findImplementation(classSym.symbol).groupBy(_.file)
      fileSource = AbsolutePath(file)
      doc <- findSemanticdb(fileSource)
      distance = TokenEditDistance.fromBuffer(fileSource, doc.text, buffer)
      impl <- locations
      implReal = impl.toRealNames(classSym, translateKey = true)
      implOccurence <- if (isClassLike(sym)) findDefOccurence(doc, impl.symbol)
      else MethodImplementation.find(sym, classSym, implReal, doc)
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
      .flatMap { loc =>
        findImplementation(loc.symbol).map(_.translateAsSeenFrom(loc))
      }
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
    signature match {
      case classSig: ClassSignature =>
        val allLocations = classSig.parents.collect {
          case t: TypeRef =>
            val loc = ClassLocation(symbol, filePath, t, classSig)
            t.symbol -> loc
        }
        allLocations
      case _ =>
        Seq.empty
    }
  }
}

object ImplementationProvider {

  def classFromSymbol(
      info: SymbolInformation,
      semanticDb: TextDocument
  ): Option[SymbolInformation] = {
    if (isClassLike(info)) {
      Some(info)
    } else {
      val definitionsFound = semanticDb.symbols.filter { defn =>
        info.symbol.startsWith(defn.symbol) && isClassLike(defn)
      }
      if (definitionsFound.isEmpty) {
        None
      } else {
        Some(definitionsFound.maxBy(_.symbol.length()))
      }
    }
  }

  def findDefOccurence(
      semanticDb: TextDocument,
      symbol: String
  ): Option[SymbolOccurrence] = {
    semanticDb.occurrences.find(
      occ => occ.role.isDefinition && occ.symbol == symbol
    )
  }

  def findSymbol(
      semanticDb: TextDocument,
      symbol: String
  ): Option[SymbolInformation] = {
    semanticDb.symbols.find(
      sym => sym.symbol == symbol
    )
  }

  def isClassLike(info: SymbolInformation) =
    info.isObject || info.isClass || info.isTrait

  def enrichSignature(
      signature: Signature,
      semanticDb: TextDocument
  ): Signature = {
    signature match {
      case methodSignature: MethodSignature =>
        enrichSignature(methodSignature, semanticDb)
      case _ => signature
    }
  }

  def enrichSignature(
      signature: MethodSignature,
      semanticDb: TextDocument
  ): MethodSignature = {
    val allParams = signature.parameterLists.map { scope =>
      val hardlinks = scope.symlinks.flatMap { sym =>
        findSymbol(semanticDb, sym)
      }
      scope.copy(hardlinks = hardlinks)
    }
    signature.copy(parameterLists = allParams)
  }
}
