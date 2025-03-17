package scala.meta.internal.query

import java.nio.file.Path

import scala.collection.mutable

import scala.meta.Dialect
import scala.meta.internal.metals.Classfile
import scala.meta.internal.metals.EmptyReportContext
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.SemanticdbDefinition
import scala.meta.internal.mtags.GlobalSymbolIndex
import scala.meta.internal.mtags.Symbol
import scala.meta.internal.semanticdb.Scala.Descriptor
import scala.meta.internal.semanticdb.Scala.DescriptorParser
import scala.meta.internal.semanticdb.Scala.Symbols
import scala.meta.io.AbsolutePath
import scala.meta.pc.SymbolSearchVisitor

import org.eclipse.lsp4j
import org.eclipse.lsp4j.SymbolKind

class QuerySearchVisitor(
    index: GlobalSymbolIndex,
    symbolTypes: Set[SymbolType],
    query: String,
    enableDebug: Boolean,
) extends SymbolSearchVisitor {
  private val lowerCaseQuery = query.toLowerCase
  private val results = mutable.ListBuffer.empty[SymbolSearchResult]
  private val packageResult = mutable.ListBuffer.empty[PackageSearchResult]
  def getResults: Seq[SymbolSearchResult] =
    results.toSeq ++ packageResult.toSeq.distinctBy(_.path)

  override def shouldVisitPackage(pkg: String): Boolean = {
    val shouldIncludePackages =
      symbolTypes.isEmpty || symbolTypes.contains(SymbolType.Package)

    if (shouldIncludePackages && matchesQuery(pkg)) {
      results +=
        PackageSearchResult(
          name = pkg.substring(pkg.lastIndexOf('/') + 1),
          path = fqcn(pkg),
        )
    }
    true // Continue searching even if this package doesn't match
  }

  override def visitWorkspacePackage(owner: String, name: String): Int = {
    if (matchesQuery(name)) {
      packageResult += PackageSearchResult(
        name,
        s"$owner$name".replace('/', '.'),
      )
      1
    } else 0
  }

  private val isVisited: mutable.Set[AbsolutePath] =
    mutable.Set.empty[AbsolutePath]

  override def visitClassfile(pkg: String, filename: String): Int = {
    // Only process the classfile if we're interested in classes/objects
    if (
      symbolTypes.isEmpty ||
      symbolTypes.exists(t =>
        t == SymbolType.Class || t == SymbolType.Object || t == SymbolType.Trait
      )
    ) {

      var size = 0

      definition(pkg, filename).foreach {
        case (path, dialect) if !isVisited(path) =>
          isVisited += path
          val input = path.toInput
          // @kasiaMarek: I think this shouldn't be needed,
          // we should make definition return the type instead
          SemanticdbDefinition.foreach(
            input,
            dialect,
            includeMembers = false,
          ) { semanticdbDefn =>
            lazy val kind =
              kindToTypeString(semanticdbDefn.info.kind.toLsp).getOrElse(
                SymbolType.Unknown(semanticdbDefn.info.kind.toString)
              )

            if (
              matchesQuery(
                semanticdbDefn.info.displayName
              ) && (symbolTypes.isEmpty || symbolTypes.contains(kind))
            ) {
              results +=
                ClassOrObjectSearchResult(
                  name = semanticdbDefn.info.displayName,
                  path = fqcn(semanticdbDefn.info.symbol).stripSuffix("."),
                  symbolType = kind,
                )
              size += 1
            }

          }(EmptyReportContext)
        case _ =>
      }

      size
    } else 0
  }

  override def visitWorkspaceSymbol(
      path: Path,
      symbol: String,
      kind: SymbolKind,
      range: lsp4j.Range,
  ): Int = {
    val (desc, owner) = DescriptorParser(symbol)
    val symbolName = desc.name.value
    debug(
      s"Encountered workspace symbol: $symbol, desc: $desc, kind: $kind, range: $range, symbolName: $symbolName, owner: $owner"
    )

    lazy val symbolType =
      kindToTypeString(kind).getOrElse(SymbolType.Unknown(kind.toString))

    if (
      matchesQuery(symbolName) && (symbolTypes.isEmpty || symbolTypes.contains(
        symbolType
      ))
    ) {
      results +=
        WorkspaceSymbolSearchResult(
          name = symbolName,
          path = s"${fqcn(owner)}$symbolName",
          symbolType = symbolType,
          location = path.toUri.toString,
        )
      1
    } else 0

  }

  private def debug(string: String): Unit = if (enableDebug) pprint.log(string)

  override def isCancelled(): Boolean = false

  private def matchesQuery(str: String): Boolean = {
    str.toLowerCase.contains(lowerCaseQuery)
  }

  private def definition(
      pkg: String,
      filename: String,
  ): List[(AbsolutePath, Dialect)] = {
    val nme = Classfile.name(filename)
    val tpe = Symbol(Symbols.Global(pkg, Descriptor.Type(nme)))
    val forTpe = index.topLevels(tpe)
    val term = Symbol(Symbols.Global(pkg, Descriptor.Term(nme)))
    val forTerm = index.topLevels(term)
    forTpe ++ forTerm
  }

  /**
   * Convert SymbolKind to a string representation.
   */
  private def kindToTypeString(kind: SymbolKind): Option[SymbolType] =
    kind match {
      case SymbolKind.Class => Some(SymbolType.Class)
      case SymbolKind.Interface => Some(SymbolType.Trait)
      case SymbolKind.Object => Some(SymbolType.Object)
      case SymbolKind.Method => Some(SymbolType.Method)
      case SymbolKind.Function => Some(SymbolType.Function)
      case SymbolKind.Package => Some(SymbolType.Package)
      case _ => None
    }

  private def fqcn(symbol: String) =
    symbol.replace('/', '.').replace('#', '.').replace('$', '.')
}
