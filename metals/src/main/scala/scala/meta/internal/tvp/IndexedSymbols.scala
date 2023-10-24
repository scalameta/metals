package scala.meta.internal.tvp

import java.io.UncheckedIOException

import scala.collection.concurrent.TrieMap

import scala.meta.Dialect
import scala.meta.internal.io.FileIO
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ReportContext
import scala.meta.internal.metals.SemanticdbFeatureProvider
import scala.meta.internal.metals.Time
import scala.meta.internal.metals.Timer
import scala.meta.internal.mtags.Mtags
import scala.meta.internal.mtags.SemanticdbPath
import scala.meta.internal.mtags.Symbol
import scala.meta.internal.mtags.SymbolDefinition
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.internal.semanticdb.TextDocuments
import scala.meta.io.AbsolutePath

class IndexedSymbols(isStatisticsEnabled: Boolean)(implicit rc: ReportContext)
    extends SemanticdbFeatureProvider {

  private val mtags = new Mtags()
  // Used for workspace, is eager
  private val workspaceCache = TrieMap.empty[
    AbsolutePath,
    AllSymbols,
  ]

  type TopLevel = SymbolDefinition
  type ToplevelSymbol = String
  type AllSymbols = Array[TreeViewSymbolInformation]

  /* Used for dependencies lazily calculates all symbols in a jar.
   * At the start it only contains the definition of a toplevel Symbol, later
   * resolves to information about all symbols contained in the top level.
   */
  private val jarCache = TrieMap.empty[
    AbsolutePath,
    TrieMap[ToplevelSymbol, Either[TopLevel, AllSymbols]],
  ]

  private val filteredSymbols: Set[SymbolInformation.Kind] = Set(
    SymbolInformation.Kind.CONSTRUCTOR,
    SymbolInformation.Kind.PARAMETER,
    SymbolInformation.Kind.TYPE_PARAMETER,
  )

  override def onChange(docs: TextDocuments, path: AbsolutePath): Unit = {
    val all = docs.documents.flatMap { doc =>
      val existing = doc.occurrences.collect {
        case occ if occ.role.isDefinition => occ.symbol
      }.toSet
      doc.symbols.distinct.collect {
        case info if existing(info.symbol) && !filteredSymbols(info.kind) =>
          TreeViewSymbolInformation(
            info.symbol,
            info.kind,
            info.properties,
          )
      }
    }.toList
    workspaceCache.put(path, all.toArray)
  }

  override def onDelete(path: SemanticdbPath): Unit = {
    workspaceCache.remove(path.absolutePath)
  }

  def reset(): Unit = {
    jarCache.clear()
    workspaceCache.clear()
  }

  def withTimer[T](label: String)(f: => T): T = {
    val timer = new Timer(Time.system)
    val result = f
    if (isStatisticsEnabled) {
      scribe.info(s"$timer - $label")
    }
    result
  }

  /**
   * We load all symbols for workspace eagerly
   *
   * @param in the input file to index
   * @param dialect dialect to parse the file with
   * @return list of tree view symbols within the file
   */
  def workspaceSymbols(
      in: AbsolutePath,
      symbol: String,
  ): Iterator[TreeViewSymbolInformation] = withTimer(s"$in/!$symbol") {
    val syms = workspaceCache
      .getOrElse(in, Array.empty)
    if (Symbol(symbol).isRootPackage) syms.iterator
    else
      syms.collect {
        case defn if defn.symbol.startsWith(symbol) => defn
      }.iterator
  }

  /**
   * Lazily calculate symbols in a jar.
   *
   * @param in the input jar
   * @param symbol symbol we want to calculate members for
   * @param dialect dialect to use for the jar
   * @return all topelevels for root and up to grandchildren for other symbols
   */
  def jarSymbols(
      in: AbsolutePath,
      symbol: String,
      dialect: Dialect,
  ): Iterator[TreeViewSymbolInformation] = withTimer(s"$in/!$symbol") {
    lazy val potentialSourceJar =
      in.parent.resolve(in.filename.replace(".jar", "-sources.jar"))
    if (!in.isSourcesJar && !potentialSourceJar.exists) {
      Iterator.empty[TreeViewSymbolInformation]
    } else {
      val realIn = if (!in.isSourcesJar) potentialSourceJar else in
      val jarSymbols = jarCache.getOrElseUpdate(
        realIn, {
          val toplevels = toplevelsAt(in, dialect)
            .map(defn => defn.definitionSymbol.value -> Left(defn))

          TrieMap.empty[
            String,
            Either[TopLevel, AllSymbols],
          ] ++ toplevels
        },
      )

      def toplevelOwner(symbol: Symbol): Symbol = {
        if (symbol.isPackage) symbol
        else if (jarSymbols.contains(symbol.value)) symbol
        else if (symbol.owner.isPackage) symbol
        else toplevelOwner(symbol.owner)
      }

      val parsedSymbol = Symbol(symbol)
      // if it's a package we'll collect all the children
      if (parsedSymbol.isPackage) {
        jarSymbols.values
          .collect {
            // root package doesn't need to calculate any members, they will be calculated lazily
            case Left(defn) if parsedSymbol.isRootPackage =>
              Array(toTreeView(defn))
            case Right(list) if parsedSymbol.isRootPackage => list
            case cached =>
              symbolsForPackage(cached, dialect, jarSymbols, parsedSymbol)
          }
          .flatten
          .iterator
      } else {
        jarSymbols.get(toplevelOwner(Symbol(symbol)).value) match {
          case Some(Left(toplevelOnly)) =>
            val allSymbols = members(toplevelOnly.path, dialect).map(toTreeView)
            jarSymbols.put(
              toplevelOnly.definitionSymbol.value,
              Right(allSymbols),
            )
            allSymbols.iterator
          case Some(Right(calculated)) =>
            calculated.iterator
          case _ => Iterator.empty[TreeViewSymbolInformation]
        }
      }
    }
  }

  private def symbolsForPackage(
      cached: Either[TopLevel, AllSymbols],
      dialect: Dialect,
      jarSymbols: TrieMap[String, Either[TopLevel, AllSymbols]],
      symbol: Symbol,
  ): Array[TreeViewSymbolInformation] =
    cached match {
      case Left(toplevel)
          if toplevel.definitionSymbol.value.startsWith(symbol.value) =>
        // we need to check if we have grandchildren and the nodes are exapandable
        if (toplevel.definitionSymbol.owner == symbol) {
          val children =
            members(toplevel.path, dialect).map(toTreeView)
          jarSymbols.put(
            toplevel.definitionSymbol.value,
            Right(children),
          )
          children
        } else {
          Array(toTreeView(toplevel))
        }
      case Right(allSymbols) => allSymbols
      case _ => Array.empty[TreeViewSymbolInformation]
    }

  private def members(
      path: AbsolutePath,
      dialect: Dialect,
  ): Array[SymbolDefinition] = {
    symbolsAt(path, dialect)
      .filter(defn =>
        defn.kind.isEmpty || !defn.kind.exists(kind =>
          kind.isParameter || kind.isTypeParameter
        )
      )
      .toArray
  }

  private def toTreeView(
      symDef: SymbolDefinition
  ): TreeViewSymbolInformation = {
    val kind = symDef.kind match {
      case Some(SymbolInformation.Kind.UNKNOWN_KIND) | None =>
        if (symDef.definitionSymbol.isMethod) SymbolInformation.Kind.METHOD
        else if (symDef.definitionSymbol.isType) SymbolInformation.Kind.CLASS
        else if (symDef.definitionSymbol.isTypeParameter)
          SymbolInformation.Kind.TYPE_PARAMETER
        else SymbolInformation.Kind.OBJECT
      case Some(knownKind) => knownKind
    }
    TreeViewSymbolInformation(
      symDef.definitionSymbol.value,
      kind,
      symDef.properties,
    )
  }

  private def toplevelsAt(
      path: AbsolutePath,
      dialect: Dialect,
  ): List[SymbolDefinition] = {

    def indexJar(jar: AbsolutePath) = {
      FileIO.withJarFileSystem(jar, create = false) { root =>
        try {
          root.listRecursive.toList.collect {
            case source if source.isFile =>
              (source, mtags.toplevels(source.toInput, dialect).symbols)
          }
        } catch {
          // this happens in broken jars since file from FileWalker should exists
          case _: UncheckedIOException => Nil
        }
      }
    }

    val pathSymbolInfos = if (path.isSourcesJar) {
      indexJar(path)
    } else {
      List((path, mtags.toplevels(path.toInput, dialect).symbols))
    }
    pathSymbolInfos.collect { case (path, infos) =>
      infos.map { info =>
        SymbolDefinition(
          Symbol("_empty_"),
          Symbol(info.symbol),
          path,
          dialect,
          None,
          Some(info.kind),
          info.properties,
        )
      }
    }.flatten
  }

  private def symbolsAt(
      path: AbsolutePath,
      dialect: Dialect,
  ): List[SymbolDefinition] = {
    val document = mtags.allSymbols(path, dialect)
    document.symbols.map { info =>
      SymbolDefinition(
        Symbol("_empty_"),
        Symbol(info.symbol),
        path,
        dialect,
        None,
        Some(info.kind),
        info.properties,
      )
    }.toList

  }

}
