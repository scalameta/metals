package scala.meta.internal.tvp

import scala.collection.concurrent.TrieMap

import scala.meta.Dialect
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.Time
import scala.meta.internal.metals.Timer
import scala.meta.internal.mtags.GlobalSymbolIndex
import scala.meta.internal.mtags.Symbol
import scala.meta.internal.mtags.SymbolDefinition
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.io.AbsolutePath

class IndexedSymbols(index: GlobalSymbolIndex, isStatisticsEnabled: Boolean) {

  // Used for workspace, is eager
  private val workspaceCache = TrieMap.empty[
    AbsolutePath,
    Array[TreeViewSymbolInformation],
  ]

  type TopLevel = SymbolDefinition
  type AllSymbols = Array[TreeViewSymbolInformation]
  // Used for dependencies, is lazy, TopLevel is changed to AllSymbols when needed
  private val jarCache = TrieMap.empty[
    AbsolutePath,
    TrieMap[String, Either[TopLevel, AllSymbols]],
  ]

  def clearCache(path: AbsolutePath): Unit = {
    jarCache.remove(path)
    workspaceCache.remove(path)
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
      dialect: Dialect,
  ): Iterator[TreeViewSymbolInformation] = withTimer(s"$in/!$symbol") {
    val syms = workspaceCache
      .getOrElseUpdate(
        in,
        members(in, dialect).map(toTreeView),
      )
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
          val toplevels = index
            .toplevelsAt(in, dialect)
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
            jarSymbols.put(symbol, Right(allSymbols))
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
    index
      .symbolsAt(path, dialect)
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

}
