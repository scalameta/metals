package scala.meta.internal.implementation

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.TextDocumentPositionParams
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.mtags.{Symbol => MSymbol}
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath
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
import scala.meta.internal.semanticdb.Scala._

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

    def findSemanticDbForSymbol(symbol: String): Option[TextDocument] = {
      for {
        symbolDefinition <- index.definition(MSymbol(symbol))
        document <- findSemanticdb(symbolDefinition.path)
      } yield {
        document
      }
    }

    val locations = for {
      currentDoc <- findSemanticdb(source).toList
      positionOccurrence = definitionProvider.positionOccurrence(
        source,
        params,
        currentDoc
      )
      occ <- positionOccurrence.occurrence.toList
    } yield {
      val definitionDocument = if (currentDoc.definesSymbol(occ.symbol)) {
        Some(currentDoc)
      } else {
        findSemanticDbForSymbol(occ.symbol)
      }
      val context = definitionDocument match {
        case None =>
          val allParentClasses = implementationsInPath.asScala.flatMap {
            case (path, map) => map.keySet
          }.toSet
          globalTable.indexFor(source, allParentClasses)
        case Some(textDocument) =>
          // TODO this context should also include type mappings
          Some(LocalInheritanceContext(textDocument))
      }
      findLocations(occ.symbol, context)
    }
    locations.flatten.toList
  }

  def findLocations(
      symbol: String,
      context: Option[InheritanceContext]
  ): Iterable[Location] = {
    for {
      classContext <- context.toList
      plainSym <- classContext.findSymbol(symbol).toList
      sym = plainSym.copy(
        signature =
          enrichSignature(plainSym.signature, classContext.findSymbol _)
      )
      classSym <- classFromSymbol(sym, classContext.findSymbol _).toIterable
      (file, locations) <- findImplementation(classSym.symbol, classContext)
        .groupBy(_.file)
      realFile <- file.toIterable
      fileSource = AbsolutePath(realFile)
      doc <- findSemanticdb(fileSource).toIterable
      distance = TokenEditDistance.fromBuffer(fileSource, doc.text, buffer)
      impl <- locations
      implReal = impl.toRealNames(classSym, translateKey = true)
      implOccurence <- if (isClassLike(sym))
        findDefOccurence(doc, impl.symbol)
      else MethodImplementation.find(sym, classSym, implReal, doc)
      range <- implOccurence.range
      revised <- distance.toRevised(range.toLSP)
      uri = realFile.toUri.toString
    } yield new Location(uri, revised)
  }

  private def findSemanticdb(fileSource: AbsolutePath): Option[TextDocument] =
    semanticdbs
      .textDocument(fileSource)
      .documentIncludingStale

  private def findImplementation(
      symbol: String,
      classContext: InheritanceContext
  ): Set[ClassLocation] = {
    val workspaceImpl = for {
      (_, symbols) <- implementationsInPath.asScala
      symbolImpls <- symbols
        .get(symbol)
        .toList
      impl <- symbolImpls
    } yield impl
    val fromDependendecies =
      classContext.inheritance.getOrElse(symbol, Set.empty)

    val translatedFromDependencies = classContext
      .typeMapping(symbol)
      .flatMap(classContext.inheritance.get(_))
      .getOrElse(Set.empty)

    val directImpl = workspaceImpl ++ fromDependendecies ++ translatedFromDependencies

    directImpl.toSet ++ directImpl
      .flatMap { loc =>
        val symTranslated =
          classContext.reverseTypeMapping(loc.symbol).getOrElse(loc.symbol)
        findImplementation(
          symTranslated,
          classContext
        ).map(_.translateAsSeenFrom(loc))
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
        Some(workspace.resolve(doc.uri))
      ).toList
    } yield parent

    allParents.groupBy(_._1).map {
      case (symbol, locations) =>
        symbol -> locations.map(_._2).toSet
    }
  }
}

object ImplementationProvider {

  def parentsFromSignature(
      symbol: String,
      signature: Signature,
      filePath: Option[AbsolutePath]
  ): Seq[(String, ClassLocation)] = {
    signature match {
      case classSig: ClassSignature =>
        val allLocations = classSig.parents.collect {
          case t: TypeRef =>
            val loc =
              ClassLocation(symbol, filePath.map(_.toNIO), t, classSig)
            t.symbol -> loc
        }
        allLocations
      case _ =>
        Seq.empty
    }
  }

  def classFromSymbol(
      info: SymbolInformation,
      findSymbol: String => Option[SymbolInformation]
  ): Option[SymbolInformation] = {
    if (isClassLike(info)) {
      Some(info)
    } else {
      findSymbol(info.symbol.owner).filter(isClassLike)
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
    info.isObject || info.isClass || info.isTrait || info.isType

  def enrichSignature(
      signature: Signature,
      findSymbol: String => Option[SymbolInformation]
  ): Signature = {
    signature match {
      case methodSignature: MethodSignature =>
        enrichSignature(methodSignature, findSymbol)
      case _ => signature
    }
  }

  def enrichSignature(
      signature: MethodSignature,
      findSymbol: String => Option[SymbolInformation]
  ): MethodSignature = {
    val allParams = signature.parameterLists.map { scope =>
      val hardlinks = scope.symlinks.flatMap { sym =>
        findSymbol(sym)
      }
      scope.copy(hardlinks = hardlinks)
    }
    signature.copy(parameterLists = allParams)
  }
}
