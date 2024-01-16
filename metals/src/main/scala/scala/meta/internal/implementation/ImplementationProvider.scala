package scala.meta.internal.implementation

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.control.NonFatal

import scala.meta.internal.io.FileIO
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.Compilers
import scala.meta.internal.metals.DefinitionProvider
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ReportContext
import scala.meta.internal.metals.ScalaVersionSelector
import scala.meta.internal.metals.ScalaVersions
import scala.meta.internal.metals.SemanticdbFeatureProvider
import scala.meta.internal.mtags.GlobalSymbolIndex
import scala.meta.internal.mtags.IndexingResult
import scala.meta.internal.mtags.Mtags
import scala.meta.internal.mtags.OverriddenSymbol
import scala.meta.internal.mtags.ResolvedOverriddenSymbol
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.mtags.SymbolDefinition
import scala.meta.internal.mtags.UnresolvedOverriddenSymbol
import scala.meta.internal.mtags.{Symbol => MSymbol}
import scala.meta.internal.parsing.Trees
import scala.meta.internal.pc.PcSymbolInformation
import scala.meta.internal.pc.PcSymbolKind
import scala.meta.internal.pc.PcSymbolProperty
import scala.meta.internal.semanticdb.ClassSignature
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
    definitionProvider: DefinitionProvider,
    trees: Trees,
    scalaVersionSelector: ScalaVersionSelector,
    compilers: Compilers,
)(implicit ec: ExecutionContext, rc: ReportContext)
    extends SemanticdbFeatureProvider {
  import ImplementationProvider._

  private val globalTable = new GlobalClassTable(buildTargets)
  private val implementationsInPath =
    new ConcurrentHashMap[Path, Map[String, Set[ClassLocation]]]
  private val implementationsInDependencySources =
    new ConcurrentHashMap[String, Set[ClassLocation]]

  override def reset(): Unit = {
    implementationsInPath.clear()
  }

  override def onDelete(path: AbsolutePath): Unit = {
    implementationsInPath.remove(path.toNIO)
  }

  override def onChange(docs: TextDocuments, path: AbsolutePath): Unit = {
    if (!path.isJarFileSystem)
      implementationsInPath.compute(
        path.toNIO,
        { (_, _) => computeInheritance(docs) },
      )
  }

  def addTypeHierarchy(results: List[IndexingResult]): Unit = for {
    IndexingResult(path, _, overrides) <- results
    (overridesSymbol, overriddenSymbols) <- overrides
    overridden <- overriddenSymbols
  } addTypeHierarchyElement(path, overridesSymbol, overridden)

  def addTypeHierarchyElements(
      elements: List[(AbsolutePath, String, OverriddenSymbol)]
  ): Unit = elements.foreach { case (path, overridesSymbol, overridden) =>
    addTypeHierarchyElement(path, overridesSymbol, overridden)
  }

  private def addTypeHierarchyElement(
      path: AbsolutePath,
      overridesSymbol: String,
      overridden: OverriddenSymbol,
  ): Unit = {
    def createUpdate(
        newSymbol: ClassLocation
    ): (String, Set[ClassLocation]) => Set[ClassLocation] = {
      case (_, null) => Set(newSymbol)
      case (_, previous) => previous + newSymbol
    }
    overridden match {
      case ResolvedOverriddenSymbol(symbol) =>
        val update = createUpdate(
          ClassLocation(overridesSymbol, Some(path.toNIO))
        )
        implementationsInDependencySources.compute(symbol, update(_, _))
      case UnresolvedOverriddenSymbol(name) =>
        val update =
          createUpdate(
            ClassLocation(overridesSymbol, Some(path.toNIO))
          )
        implementationsInDependencySources.compute(name, update(_, _))
    }
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
      if (isClassLike(symbolInfo) || symbolInfo.isType) {
        parentImplLocationPairs ++= parentsFromSignature(
          symbolInfo.symbol,
          symbolInfo.signature,
          Some(workspace.resolve(document.uri)),
        )
      }
    }
    parentImplLocationPairs.groupBy(_._1).map { case (symbol, locations) =>
      symbol -> locations.map(_._2).toSet
    }
  }

  def defaultSymbolSearch(
      anyWorkspacePath: AbsolutePath,
      textDocument: TextDocument,
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
  ): Future[List[Location]] = {
    val source = params.getTextDocument.getUri.toAbsolutePath
    val locations = for {
      (symbolOccurrence, currentDocument) <-
        definitionProvider
          .symbolOccurrence(
            source,
            params.getPosition,
          )
          .toIterable
    } yield {
      // 1. Search locally for symbol
      // 2. Search inside workspace
      // 3. Search classpath via GlobalSymbolTable
      val sym = symbolOccurrence.symbol
      val dealised =
        if (sym.desc.isType) {
          symbolInfo(currentDocument, source, sym).map(
            _.map(_.dealisedSymbol).getOrElse(sym)
          )
        } else Future.successful(sym)
      dealised.flatMap { dealisedSymbol =>
        val isWorkspaceSymbol =
          (source.isWorkspaceSource(workspace) &&
            currentDocument.definesSymbol(dealisedSymbol)) ||
            findSymbolDefinition(dealisedSymbol).exists(
              _.path.isWorkspaceSource(workspace)
            )

        val inheritanceContext: InheritanceContext =
          if (!isWorkspaceSymbol) {
            // symbol is not in workspace, we search both workspace and dependencies for it
            InheritanceContext
              .fromDefinitions(implementationsInPath.asScala.toMap)
              .toGlobal(
                compilers,
                implementationsInDependencySources.asScala.toMap,
              )
          } else {
            // symbol in workspace we search the workspace for it only
            InheritanceContext.fromDefinitions(
              implementationsInPath.asScala.toMap
            )
          }
        symbolLocationsFromContext(
          dealisedSymbol,
          currentDocument,
          source,
          inheritanceContext,
        )
      }
    }

    Future.sequence(locations).map {
      _.flatten.toList
    }
  }

  def topMethodParents(
      symbol: String,
      textDocument: TextDocument,
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
  ): Seq[Location] = {
    currentClassSig.parents.flatMap {
      case parentSym: TypeRef =>
        val parentTextDocument = findSemanticDbForSymbol(parentSym.symbol)
        def search(symbol: String) =
          parentTextDocument.flatMap(findSymbol(_, symbol))
        search(parentSym.symbol).map(_.signature) match {
          case Some(parenClassSig: ClassSignature) =>
            val fromParent = methodInParentSignature(
              parenClassSig,
              bottomSymbol,
              bottomClassSig,
            )
            if (fromParent.isEmpty) {
              locationFromClass(
                bottomSymbol,
                parenClassSig,
                search,
                parentTextDocument,
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
      parentClassSig: ClassSignature,
      search: String => Option[SymbolInformation],
      parentTextDocument: Option[TextDocument],
  ): Option[Location] = {
    val matchingSymbol = MethodImplementation.findParentSymbol(
      bottomSymbolInformation,
      parentClassSig,
      search,
    )
    for {
      symbol <- matchingSymbol
      parentDoc <- parentTextDocument
      source = workspace.resolve(parentDoc.uri)
      implOccurrence <- findDefOccurrence(
        parentDoc,
        symbol,
        source,
      )
      range <- implOccurrence.range
      distance = buffer.tokenEditDistance(
        source,
        parentDoc.text,
        trees,
      )
      revised <- distance.toRevised(range.toLsp)
    } yield new Location(source.toNIO.toUri().toString(), revised)
  }

  private def symbolLocationsFromContext(
      dealised: String,
      textDocument: TextDocument,
      source: AbsolutePath,
      inheritanceContext: InheritanceContext,
  ): Future[Seq[Location]] = {

    def findImplementationSymbol(
        info: PcSymbolInformation,
        classSymbol: String,
        textDocument: TextDocument,
        implReal: ClassLocation,
    ): Option[Future[String]] = {
      def findSymbolInDoc(symbol: String) =
        textDocument.symbols.find(_.symbol == symbol)
      if (classLikeKinds(info.kind)) Some(Future(implReal.symbol))
      else {
        def tryFromDoc =
          for {
            classInfo <- findSymbolInDoc(implReal.symbol)
            declarations <- classInfo.signature match {
              case ClassSignature(_, _, _, declarations) => declarations
              case _ => None
            }
            found <- declarations.symlinks.collectFirst { sym =>
              findSymbolInDoc(sym) match {
                case Some(implInfo)
                    if implInfo.overriddenSymbols.contains(info.symbol) =>
                  sym
              }
            }
          } yield Future.successful(found)
        def pcSearch = {
          val symbol =
            s"${implReal.symbol}${info.symbol.stripPrefix(classSymbol)}"
          implReal.file
            .map(path =>
              compilers
                .infoAll(AbsolutePath(path), symbol)
                .map { allFound =>
                  allFound
                    .find(implInfo => implInfo.overridden.contains(info.symbol))
                    .map(_.symbol)
                    .getOrElse(symbol)
                }
            )
            .getOrElse(Future.successful(symbol))
        }
        tryFromDoc.orElse {
          if (implReal.symbol.isLocal) None
          else Some(pcSearch)
        }
      }
    }

    val allLocations = new ConcurrentLinkedQueue[Location]

    def findImplementationLocations(
        files: Set[Path],
        locationsByFile: Map[Path, Set[ClassLocation]],
        parentSymbol: PcSymbolInformation,
        classSymbol: String,
    ) = Future.sequence({
      for {
        file <- files
        locations = locationsByFile(file)
        implPath = AbsolutePath(file)
        implDocument <- findSemanticdb(implPath, handleJars = true).toList
      } yield {
        for {
          symbols <- Future.sequence(
            locations.flatMap(
              findImplementationSymbol(
                parentSymbol,
                classSymbol,
                implDocument,
                _,
              )
            )
          )
        } yield {
          for {
            sym <- symbols
            symInfo <- implDocument.symbols.find(_.symbol == sym)
            if (!symInfo.isType || parentSymbol.kind == PcSymbolKind.TYPE)
            implOccurrence <- findDefOccurrence(
              implDocument,
              sym,
              implPath,
            ).toList
            range <- implOccurrence.range
            revised <-
              if (implPath.isJarFileSystem) {
                Some(range.toLsp)
              } else {
                val distance = buffer.tokenEditDistance(
                  implPath,
                  implDocument.text,
                  trees,
                )
                distance.toRevised(range.toLsp)
              }
          } { allLocations.add(new Location(file.toUri.toString, revised)) }
        }
      }
    })

    lazy val cores = Runtime.getRuntime().availableProcessors()
    val splitJobs =
      symbolInfo(textDocument, source, dealised).flatMap { optSymbolInfo =>
        (for {
          symbolInfo <- optSymbolInfo
          symbolClass <- classFromSymbol(symbolInfo)
        } yield {
          for {
            locationsByFile <- findImplementation(
              symbolClass,
              inheritanceContext,
              source.toNIO,
            )
            files = locationsByFile.keySet.grouped(
              Math.max(locationsByFile.size / cores, 1)
            )
            collected <-
              Future.sequence(
                files.map(
                  findImplementationLocations(
                    _,
                    locationsByFile,
                    symbolInfo,
                    symbolClass,
                  )
                )
              )
          } yield collected
        }).getOrElse(Future.successful(Iterator.empty))
      }
    splitJobs.map(_ => allLocations.asScala.toSeq)
  }

  private def findSemanticdb(
      fileSource: AbsolutePath,
      handleJars: Boolean = false,
  ): Option[TextDocument] = {
    if (fileSource.isJarFileSystem)
      if (!handleJars) None
      else Some(semanticdbForJarFile(fileSource))
    else
      semanticdbs
        .textDocument(fileSource)
        .documentIncludingStale
  }

  private def semanticdbForJarFile(fileSource: AbsolutePath) = {
    val dialect = ScalaVersions.dialectForDependencyJar(fileSource.filename)
    FileIO.slurp(fileSource, StandardCharsets.UTF_8)
    val textDocument = Mtags.index(fileSource, dialect)
    textDocument
  }

  private def findImplementation(
      symbol: String,
      classContext: InheritanceContext,
      file: Path,
  ): Future[Map[Path, Set[ClassLocation]]] = {
    val visited = mutable.Set.empty[String]

    def loop(
        symbol: String,
        currentPath: Option[Path],
    ): Future[Set[ClassLocation]] = {
      visited.add(symbol)
      scribe.debug(s"searching for implementations for symbol $symbol")
      val directImplementations =
        classContext
          .getLocations(symbol)
          .map(_.filterNot { loc =>
            // we are not interested in local symbols from outside the workspace
            (loc.symbol.isLocal && loc.file.isEmpty) ||
            // for local symbols, inheritance should only be picked up in the same file
            // otherwise there can be a name collision between files
            // local1' is from file A, local2 extends local1''
            // but both local2 and local1'' are from file B
            // clearly, local2 shouldn't be considered for local1'
            (symbol.isLocal && loc.symbol.isLocal && loc.file != currentPath)
          })
      directImplementations.flatMap { directImplementations =>
        Future
          .sequence(
            directImplementations
              .withFilter(loc =>
                (!visited(
                  loc.symbol
                ) && loc.symbol.desc.isType) || loc.symbol.isLocal
              )
              .map { loc =>
                loop(loc.symbol, loc.file)
              }
          )
          .map(rec => directImplementations ++ rec.flatten)
      }
    }

    loop(symbol, Some(file)).map(_.groupBy(_.file).collect {
      case (Some(path), locs) =>
        path -> locs
    })
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

  private def classFromSymbol(info: PcSymbolInformation): Option[String] =
    if (classLikeKinds(info.kind)) Some(info.dealisedSymbol)
    else info.classOwner

  private def findDefOccurrence(
      semanticDb: TextDocument,
      symbol: String,
      source: AbsolutePath,
  ): Option[SymbolOccurrence] = {
    def isDefinitionOccurrence(occ: SymbolOccurrence) =
      occ.role.isDefinition && occ.symbol == symbol

    semanticDb.occurrences
      .find(isDefinitionOccurrence)
      .orElse(
        Mtags
          .allToplevels(source.toInput, scalaVersionSelector.getDialect(source))
          .occurrences
          .find(isDefinitionOccurrence)
      )
  }

  private def symbolInfo(
      textDocument: TextDocument,
      source: AbsolutePath,
      symbol: String,
  ): Future[Option[PcSymbolInformation]] =
    if (symbol.isLocal) {
      (for {
        info <- textDocument.symbols.find(_.symbol == symbol)
      } yield {
        info.signature match {
          case typeSig: TypeSignature =>
            typeSig.upperBound match {
              case tr: TypeRef =>
                symbolInfo(textDocument, source, tr.symbol).map(
                  _.map(_.copy(symbol = symbol))
                )
              case _ => Future.successful(None)
            }
          case _ => Future.successful(Some(toPcSymbolInfo(textDocument, info)))
        }
      }).getOrElse(Future.successful(None))
    } else compilers.info(source, symbol)

  private def toPcSymbolInfo(
      textDocument: TextDocument,
      info: SymbolInformation,
  ): PcSymbolInformation = {
    val parents =
      info.signature match {
        case ClassSignature(_, parents, _, _) =>
          parents.collect { case t: TypeRef => t.symbol }.toList
        case _ => Nil
      }
    val classOwner =
      textDocument.symbols.collectFirst { classInfo =>
        classInfo.signature match {
          case ClassSignature(_, _, _, declarations)
              if declarations.exists(_.symlinks.contains(info.symbol)) =>
            classInfo.symbol
        }
      }

    PcSymbolInformation(
      symbol = info.symbol,
      kind = PcSymbolKind.values
        .find(_.id == info.kind.value)
        .getOrElse(PcSymbolKind.UNKNOWN_KIND),
      parents = parents,
      dealisedSymbol = info.symbol,
      classOwner = classOwner,
      overridden = info.overriddenSymbols.toList,
      properties = if (info.isAbstract) List(PcSymbolProperty.ABSTRACT) else Nil,
    )
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

  private def findSymbol(
      semanticDb: TextDocument,
      symbol: String,
  ): Option[SymbolInformation] = {
    semanticDb.symbols
      .find(sym => sym.symbol == symbol)
  }

  def parentsFromSignature(
      symbol: String,
      signature: Signature,
      filePath: Option[AbsolutePath],
  ): Seq[(String, ClassLocation)] = {

    def fromClassSignature(
        classSig: ClassSignature
    ): Seq[(String, ClassLocation)] = {
      classSig.parents.collect { case t: TypeRef =>
        t.symbol -> ClassLocation(symbol, filePath.map(_.toNIO))
      }
    }

    def fromTypeSignature(typeSig: TypeSignature) = {
      typeSig.upperBound match {
        case tr: TypeRef =>
          Seq(
            tr.symbol -> ClassLocation(
              symbol,
              filePath.map(_.toNIO),
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
      case _ =>
        Seq.empty
    }
  }

  def isClassLike(info: SymbolInformation): Boolean =
    info.isObject || info.isClass || info.isTrait || info.isInterface

  val classLikeKinds: Set[PcSymbolKind.Value] = Set(
    PcSymbolKind.OBJECT,
    PcSymbolKind.CLASS,
    PcSymbolKind.TRAIT,
    PcSymbolKind.INTERFACE,
  )

}
