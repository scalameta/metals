package scala.meta.internal.search

import scala.util.control.NonFatal

import scala.meta.internal.implementation.MethodImplementation
import scala.meta.internal.implementation.TextDocumentWithPath
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ScalaVersionSelector
import scala.meta.internal.mtags.GlobalSymbolIndex
import scala.meta.internal.mtags.Mtags
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.mtags.SymbolDefinition
import scala.meta.internal.mtags.{Symbol => MSymbol}
import scala.meta.internal.search.SymbolHierarchyOps._
import scala.meta.internal.semanticdb.ClassSignature
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.semanticdb.TypeRef
import scala.meta.internal.semanticdb.XtensionSemanticdbSymbolInformation
import scala.meta.internal.symtab.GlobalSymbolTable
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.Location

class SymbolHierarchyOps(
    workspace: AbsolutePath,
    buildTargets: BuildTargets,
    semanticdbs: () => Semanticdbs,
    index: GlobalSymbolIndex,
    scalaVersionSelector: ScalaVersionSelector,
    buffer: Buffers,
) {
  private val globalTable = new GlobalClassTable(buildTargets)
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

  private def findSemanticdb(fileSource: AbsolutePath): Option[TextDocument] = {
    if (fileSource.isJarFileSystem) None
    else
      semanticdbs()
        .textDocument(fileSource)
        .documentIncludingStale
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
        scalaVersionSelector,
      )
      range <- implOccurrence.range
      distance = buffer.tokenEditDistance(
        source,
        parentDoc.text,
        scalaVersionSelector,
      )
      revised <- distance.toRevised(range.toLsp)
    } yield new Location(source.toNIO.toUri().toString(), revised)
  }
}

object SymbolHierarchyOps {
  def findSymbol(
      semanticDb: TextDocument,
      symbol: String,
  ): Option[SymbolInformation] = {
    semanticDb.symbols
      .find(sym => sym.symbol == symbol)
  }

  implicit class XtensionGlobalSymbolTable(symtab: GlobalSymbolTable) {
    def safeInfo(symbol: String): Option[SymbolInformation] =
      try {
        symtab.info(symbol)
      } catch {
        case NonFatal(_) => None
      }
  }

  def isClassLike(info: SymbolInformation): Boolean =
    info.isObject || info.isClass || info.isTrait || info.isInterface

  def findDefOccurrence(
      semanticDb: TextDocument,
      symbol: String,
      source: AbsolutePath,
      scalaVersionSelector: ScalaVersionSelector,
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
}
