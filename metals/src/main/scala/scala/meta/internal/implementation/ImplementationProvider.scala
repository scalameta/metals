package scala.meta.internal.implementation

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

import scala.collection.mutable
import scala.util.control.NonFatal

import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.DefinitionProvider
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.GlobalSymbolIndex
import scala.meta.internal.mtags.Mtags
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.mtags.SymbolDefinition
import scala.meta.internal.mtags.{Symbol => MSymbol}
import scala.meta.internal.semanticdb.ClassSignature
import scala.meta.internal.semanticdb.MethodSignature
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.Signature
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.semanticdb.TextDocuments
import scala.meta.internal.semanticdb.TypeRef
import scala.meta.internal.semanticdb.TypeSignature
import scala.meta.internal.symtab.GlobalSymbolTable
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.TextDocumentPositionParams

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
      path,
      { (_, _) => computeInheritance(docs) }
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

  def defaultSymbolSearch(
      textDocumentWithPath: TextDocumentWithPath
  ): String => Option[SymbolInformation] =
    defaultSymbolSearch(
      textDocumentWithPath.filePath,
      textDocumentWithPath.textDocument
    )

  def defaultSymbolSearchMemoize(
      anyWorkspacePath: AbsolutePath,
      textDocument: TextDocument
  ): String => Option[SymbolInformation] = {
    lazy val global =
      globalTable.globalSymbolTableFor(anyWorkspacePath)
    val textSymbolsMap = textDocument.symbols.map(s => s.symbol -> s).toMap
    val memoized: mutable.Map[String, SymbolInformation] = mutable.Map.empty
    symbol => {
      val result = memoized
        .get(symbol)
        .orElse(textSymbolsMap.get(symbol))
        .orElse(findSymbolInformation(symbol))
        .orElse(global.flatMap(_.safeInfo(symbol)))
      result.foreach(r => memoized.put(symbol, r))
      result
    }
  }

  def defaultSymbolSearch(
      anyWorkspacePath: AbsolutePath,
      textDocument: TextDocument
  ): String => Option[SymbolInformation] = {
    lazy val global =
      globalTable.globalSymbolTableFor(anyWorkspacePath)
    symbol => {
      textDocument.symbols
        .find(_.symbol == symbol)
        .orElse(findSymbolInformation(symbol))
        .orElse(global.flatMap(_.safeInfo(symbol)))
    }
  }

  def implementations(
      params: TextDocumentPositionParams
  ): List[Location] = {
    val source = params.getTextDocument.getUri.toAbsolutePath
    val locations = for {
      (symbolOccurrence, currentDocument) <-
        definitionProvider
          .symbolOccurrence(
            source,
            params.getPosition
          )
          .toIterable
    } yield {
      // 1. Search locally for symbol
      // 2. Search inside workspace
      // 3. Search classpath via GlobalSymbolTable
      val symbolSearch = defaultSymbolSearch(source, currentDocument)
      val sym = symbolOccurrence.symbol
      val dealiased =
        if (sym.desc.isType) dealiasClass(sym, symbolSearch) else sym

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

  def topMethodParents(
      symbol: String,
      textDocument: TextDocument
  ): Seq[Location] = {

    def findClassInfo(owner: String) = {
      if (owner.nonEmpty) {
        findSymbol(textDocument, owner)
      } else {
        textDocument.symbols.find { sym =>
          sym.signature match {
            case sig: ClassSignature =>
              sig.declarations.exists(_.symlinks.contains(symbol))
            case _ => false
          }
        }
      }
    }

    val results = for {
      currentInfo <- findSymbol(textDocument, symbol)
      if !isClassLike(currentInfo)
      classInfo <- findClassInfo(symbol.owner)
    } yield {
      classInfo.signature match {
        case sig: ClassSignature =>
          methodInParentSignature(sig, currentInfo, sig)
        case _ => Nil
      }
    }
    results.getOrElse(Seq.empty)
  }

  private def methodInParentSignature(
      currentClassSig: ClassSignature,
      bottomSymbol: SymbolInformation,
      bottomClassSig: ClassSignature,
      childASF: Map[String, String] = Map.empty
  ): Seq[Location] = {
    currentClassSig.parents.flatMap {
      case parentSym: TypeRef =>
        val parentTextDocument = findSemanticDbForSymbol(parentSym.symbol)
        def search(symbol: String) =
          parentTextDocument.flatMap(findSymbol(_, symbol))
        val parentASF =
          AsSeenFrom.calculateAsSeenFrom(
            parentSym,
            currentClassSig.typeParameters
          )
        val asSeenFrom = AsSeenFrom.translateAsSeenFrom(childASF, parentASF)
        search(parentSym.symbol).map(_.signature) match {
          case Some(parenClassSig: ClassSignature) =>
            val fromParent = methodInParentSignature(
              parenClassSig,
              bottomSymbol,
              bottomClassSig,
              asSeenFrom
            )
            if (fromParent.isEmpty) {
              locationFromClass(
                bottomSymbol,
                bottomClassSig,
                parenClassSig,
                asSeenFrom,
                search,
                parentTextDocument
              )
            } else {
              fromParent
            }
          case _ => Nil
        }

      case _ => Nil
    }
  }

  private def locationFromClass(
      bottomSymbolInformation: SymbolInformation,
      bottomClassSignature: ClassSignature,
      parentClassSig: ClassSignature,
      asSeenFrom: Map[String, String],
      search: String => Option[SymbolInformation],
      parentTextDocument: Option[TextDocument]
  ): Option[Location] = {
    val matchingSymbol = MethodImplementation.findParentSymbol(
      bottomSymbolInformation,
      bottomClassSignature,
      parentClassSig,
      asSeenFrom,
      search
    )
    for {
      symbol <- matchingSymbol
      parentDoc <- parentTextDocument
      source = workspace.resolve(parentDoc.uri)
      implOccurrence <- findDefOccurrence(
        parentDoc,
        symbol,
        source
      )
      range <- implOccurrence.range
      distance = buffer.tokenEditDistance(
        source,
        parentDoc.text
      )
      revised <- distance.toRevised(range.toLSP)
    } yield new Location(source.toNIO.toUri().toString(), revised)
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
        val symbolSearch = defaultSymbolSearch(source, implDocument)
        MethodImplementation.findInherited(
          parentSymbolInfo,
          symbolClass,
          classContext,
          implReal,
          symbolSearch
        )
      }
    }

    val allLocations = new ConcurrentLinkedQueue[Location]

    for {
      classContext <- inheritanceContext.toIterable
      plainParentSymbol <- classContext.findSymbol(symbol).toIterable
      parentSymbol = addParameterSignatures(plainParentSymbol, classContext)
      symbolClass <- classFromSymbol(parentSymbol, classContext.findSymbol)
      locationsByFile = findImplementation(
        symbolClass.symbol,
        classContext,
        source.toNIO
      )
      file <- locationsByFile.keySet.toArray.par
      locations = locationsByFile(file)
      implPath = AbsolutePath(file)
      implDocument <- findSemanticdb(implPath).toIterable
      distance = buffer.tokenEditDistance(implPath, implDocument.text)
      implLocation <- locations
      implReal = implLocation.toRealNames(symbolClass, translateKey = true)
      implSymbol <- findImplementationSymbol(
        parentSymbol,
        implDocument,
        symbolClass,
        classContext,
        implReal
      )
      if !findSymbol(implDocument, implSymbol).exists(
        _.kind == SymbolInformation.Kind.TYPE
      )
      implOccurrence <- findDefOccurrence(
        implDocument,
        implSymbol,
        source
      )
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
      classContext: InheritanceContext,
      file: Path
  ): Map[Path, Set[ClassLocation]] = {

    def loop(symbol: String, currentPath: Option[Path]): Set[ClassLocation] = {
      val directImplementations = classContext.getLocations(symbol).filterNot {
        loc =>
          // we are not interested in local symbols from outside the workspace
          (loc.symbol.isLocal && loc.file.isEmpty) ||
          // local symbols inheritance should only be picked up in the same file
          (loc.symbol.isLocal && loc.file != currentPath)
      }
      directImplementations ++ directImplementations
        .flatMap { loc =>
          val allPossible = loop(loc.symbol, loc.file)
          allPossible.map(_.translateAsSeenFrom(loc))

        }
    }

    loop(symbol, Some(file)).groupBy(_.file).collect {
      case (Some(path), locs) =>
        path -> locs
    }
  }

  private def findSymbolInformation(
      symbol: String
  ): Option[SymbolInformation] = {
    findSemanticDbForSymbol(symbol).flatMap(findSymbol(_, symbol))
  }

  def findSemanticDbWithPathForSymbol(
      symbol: String
  ): Option[TextDocumentWithPath] = {
    for {
      symbolDefinition <- findSymbolDefinition(symbol)
      document <- findSemanticdb(symbolDefinition.path)
    } yield TextDocumentWithPath(document, symbolDefinition.path)
  }

  private def findSymbolDefinition(symbol: String): Option[SymbolDefinition] = {
    index.definition(MSymbol(symbol))
  }

  private def findSemanticDbForSymbol(symbol: String): Option[TextDocument] = {
    for {
      symbolDefinition <- findSymbolDefinition(symbol)
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

  private def findSymbol(
      semanticDb: TextDocument,
      symbol: String
  ): Option[SymbolInformation] = {
    semanticDb.symbols
      .find(sym => sym.symbol == symbol)
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
              filePath.map(_.toNIO),
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
      if (scope.symlinks.size > scope.hardlinks.size) {
        val hardlinks = scope.symlinks.flatMap { sym => findSymbol(sym) }
        scope.copy(hardlinks = hardlinks)
      } else {
        scope
      }
    }
    signature.copy(parameterLists = allParams)
  }
}
