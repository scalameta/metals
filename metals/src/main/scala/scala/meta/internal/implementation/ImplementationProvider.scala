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
import scala.meta.internal.semanticdb.TypeSignature
import scala.collection.mutable
import scala.meta.internal.symtab.GlobalSymbolTable
import scala.util.control.NonFatal
import scala.meta.internal.mtags.Mtags
import java.util.concurrent.ConcurrentLinkedQueue

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

  private def computeInheritance(
      documents: TextDocuments
  ): Map[String, Set[ClassLocation]] = {
    val parentImplLocationPairs =
      new mutable.ListBuffer[(String, ClassLocation)]
    for {
      document <- documents.documents
      symbolInfo <- document.symbols
    } {
      if (isClassLike(symbolInfo)) {
        parentImplLocationPairs ++= parentsFromSignature(
          symbolInfo.symbol,
          symbolInfo.signature,
          Some(workspace.resolve(document.uri))
        )
      }
    }
    parentImplLocationPairs.groupBy(_._1).map {
      case (symbol, locations) =>
        symbol -> locations.map(_._2).toSet
    }
  }

  def implementations(
      params: TextDocumentPositionParams
  ): List[Location] = {
    val source = params.getTextDocument.getUri.toAbsolutePath
    lazy val global = globalTable.globalSymbolTableFor(source)
    val locations = for {
      currentDocument <- findSemanticdb(source).toIterable
      positionOccurrence = definitionProvider.positionOccurrence(
        source,
        params,
        currentDocument
      )
      symbolOccurrence <- {
        lazy val mtagsOccurrence = Mtags
          .allToplevels(source.toInput)
          .occurrences
          .find(_.encloses(params.getPosition))
        positionOccurrence.occurrence.orElse(mtagsOccurrence).toIterable
      }
    } yield {
      // 1. Search locally for symbol
      // 2. Search inside workspace
      // 3. Search classpath via GlobalSymbolTable
      def symbolSearch(symbol: String): Option[SymbolInformation] = {
        findSymbol(currentDocument, symbol)
          .orElse(findClassDef(symbol))
          .orElse(global.flatMap(_.safeInfo(symbol)))
      }
      val sym = symbolOccurrence.symbol
      val dealiased =
        if (sym.desc.isType) dealiasClass(sym, symbolSearch _) else sym

      val definitionDocument =
        if (currentDocument.definesSymbol(dealiased)) {
          Some(currentDocument)
        } else {
          findSemanticDbForSymbol(dealiased)
        }

      val inheritanceContext = definitionDocument match {
        // symbol is not in workspace, we only search classpath for it
        case None =>
          globalTable.globalContextFor(
            source,
            implementationsInPath.asScala.toMap
          )
        // symbol is in workspace,
        // we might need to search different places for related symbols
        case Some(textDocument) =>
          Some(
            InheritanceContext.fromDefinitions(
              symbolSearch,
              implementationsInPath.asScala.toMap
            )
          )
      }
      symbolLocationsFromContext(
        dealiased,
        source,
        inheritanceContext
      )
    }
    locations.flatten.toList
  }

  private def symbolLocationsFromContext(
      symbol: String,
      source: AbsolutePath,
      inheritanceContext: Option[InheritanceContext]
  ): Iterable[Location] = {

    def findImplementationSymbol(
        parentSymbolInfo: SymbolInformation,
        implDocument: TextDocument,
        symbolClass: SymbolInformation,
        classContext: InheritanceContext,
        implReal: ClassLocation
    ): Option[String] = {
      if (isClassLike(parentSymbolInfo))
        Some(implReal.symbol)
      else {
        lazy val global = globalTable.globalSymbolTableFor(source)
        def localSearch(symbol: String): Option[SymbolInformation] = {
          findSymbol(implDocument, symbol)
            .orElse(findClassDef(symbol))
            .orElse(global.flatMap(_.safeInfo(symbol)))
        }
        MethodImplementation.find(
          parentSymbolInfo,
          symbolClass,
          classContext,
          implReal,
          localSearch
        )
      }
    }

    import TokenEditDistance.fromBuffer
    val allLocations = new ConcurrentLinkedQueue[Location]
    for {
      classContext <- inheritanceContext.toIterable
      plainParentSymbol <- classContext.findSymbol(symbol).toIterable
      parentSymbol = addParameterSignatures(plainParentSymbol, classContext)
      symbolClass <- classFromSymbol(parentSymbol, classContext.findSymbol)
      locationsByFile = findImplementation(symbolClass.symbol, classContext)
      file <- locationsByFile.keySet.toArray.par
      locations = locationsByFile(file)
      implPath = AbsolutePath(file)
      implDocument <- findSemanticdb(implPath).toIterable
      distance = fromBuffer(implPath, implDocument.text, buffer)
      implLocation <- locations
      implReal = implLocation.toRealNames(symbolClass, translateKey = true)
      implSymbol <- findImplementationSymbol(
        parentSymbol,
        implDocument,
        symbolClass,
        classContext,
        implReal
      )
      implOccurrence <- findDefOccurrence(implDocument, implSymbol, source)
      range <- implOccurrence.range
      revised <- distance.toRevised(range.toLSP)
    } { allLocations.add(new Location(file.toUri.toString, revised)) }
    allLocations.asScala
  }

  private def findSemanticdb(fileSource: AbsolutePath): Option[TextDocument] =
    semanticdbs
      .textDocument(fileSource)
      .documentIncludingStale

  private def findImplementation(
      symbol: String,
      classContext: InheritanceContext
  ): Map[Path, Set[ClassLocation]] = {

    def loop(symbol: String): Set[ClassLocation] = {
      val directImplementations = classContext.getLocations(symbol)
      directImplementations.toSet ++ directImplementations
        .flatMap { loc =>
          val allPossible = loop(loc.symbol)
          allPossible.map(_.translateAsSeenFrom(loc))
        }
    }

    loop(symbol).groupBy(_.file).collect {
      case (Some(path), locs) =>
        path -> locs
    }
  }

  private def findClassDef(symbol: String): Option[SymbolInformation] = {
    findSemanticDbForSymbol(symbol).flatMap(findSymbol(_, symbol))
  }

  private def findSemanticDbForSymbol(symbol: String): Option[TextDocument] = {
    for {
      symbolDefinition <- index.definition(MSymbol(symbol))
      document <- findSemanticdb(symbolDefinition.path)
    } yield {
      document
    }
  }

  private def classFromSymbol(
      info: SymbolInformation,
      findSymbol: String => Option[SymbolInformation]
  ): Iterable[SymbolInformation] = {
    val classInfo = if (isClassLike(info)) {
      Some(info)
    } else {
      findSymbol(info.symbol.owner)
        .filter(info => isClassLike(info))
    }
    classInfo.map(inf => dealiasClass(inf, findSymbol))
  }

}

object ImplementationProvider {

  implicit class XtensionGlobalSymbolTable(symtab: GlobalSymbolTable) {
    def safeInfo(symbol: String): Option[SymbolInformation] =
      try {
        symtab.info(symbol)
      } catch {
        case NonFatal(_) => None
      }
  }

  def dealiasClass(
      symbol: String,
      findSymbol: String => Option[SymbolInformation]
  ): String = {
    if (symbol.desc.isType) {
      findSymbol(symbol)
        .map(inf => dealiasClass(inf, findSymbol).symbol)
        .getOrElse(symbol)
    } else {
      symbol
    }
  }

  def dealiasClass(
      info: SymbolInformation,
      findSymbol: String => Option[SymbolInformation]
  ): SymbolInformation = {
    if (info.isType) {
      info.signature match {
        case ts: TypeSignature =>
          ts.upperBound match {
            case tr: TypeRef =>
              findSymbol(tr.symbol)
                .map(dealiasClass(_, findSymbol))
                .getOrElse(info)
            case _ =>
              info
          }
        case _ => info
      }
    } else {
      info
    }
  }

  def findSymbol(
      semanticDb: TextDocument,
      symbol: String
  ): Option[SymbolInformation] = {
    semanticDb.symbols
      .find(
        sym => sym.symbol == symbol
      )
  }

  def parentsFromSignature(
      symbol: String,
      signature: Signature,
      filePath: Option[AbsolutePath]
  ): Seq[(String, ClassLocation)] = {

    def fromClassSignature(
        classSig: ClassSignature
    ): Seq[(String, ClassLocation)] = {
      val allLocations = classSig.parents.collect {
        case t: TypeRef =>
          val loc =
            ClassLocation(
              symbol,
              filePath.map(_.toNIO),
              t,
              classSig.typeParameters
            )
          t.symbol -> loc

      }
      allLocations
    }

    def fromTypeSignature(typeSig: TypeSignature) = {
      typeSig.upperBound match {
        case tr: TypeRef =>
          Seq(
            tr.symbol -> ClassLocation(
              symbol,
              None,
              tr,
              typeSig.typeParameters
            )
          )
        case _ => Seq.empty
      }
    }

    signature match {
      case classSig: ClassSignature =>
        fromClassSignature(classSig)
      case ts: TypeSignature =>
        fromTypeSignature(ts)
      case other =>
        Seq.empty
    }
  }

  def findDefOccurrence(
      semanticDb: TextDocument,
      symbol: String,
      source: AbsolutePath
  ): Option[SymbolOccurrence] = {
    def isDefinitionOccurrence(occ: SymbolOccurrence) =
      occ.role.isDefinition && occ.symbol == symbol

    semanticDb.occurrences
      .find(isDefinitionOccurrence)
      .orElse(
        Mtags
          .allToplevels(source.toInput)
          .occurrences
          .find(isDefinitionOccurrence)
      )
  }

  def isClassLike(info: SymbolInformation): Boolean =
    info.isObject || info.isClass || info.isTrait || info.isType

  def addParameterSignatures(
      plainParentSymbol: SymbolInformation,
      classContext: InheritanceContext
  ): SymbolInformation = {
    plainParentSymbol.copy(
      signature = addParameterSignatures(
        plainParentSymbol.signature,
        classContext.findSymbol
      )
    )
  }

  def addParameterSignatures(
      signature: Signature,
      findSymbol: String => Option[SymbolInformation]
  ): Signature = {
    signature match {
      case methodSignature: MethodSignature =>
        addParameterSignatures(methodSignature, findSymbol)
      case _ => signature
    }
  }

  def addParameterSignatures(
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
