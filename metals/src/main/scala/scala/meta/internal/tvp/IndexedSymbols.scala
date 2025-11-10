package scala.meta.internal.tvp

import java.io.UncheckedIOException

import scala.collection.concurrent.TrieMap

import scala.meta.Dialect
import scala.meta._
import scala.meta.inputs.Input
import scala.meta.internal.io.FileIO
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ReportContext
import scala.meta.internal.metals.Time
import scala.meta.internal.metals.Timer
import scala.meta.internal.mtags.Mtags
import scala.meta.internal.mtags.ScalaMtags
import scala.meta.internal.mtags.Symbol
import scala.meta.internal.mtags.SymbolDefinition
import scala.meta.internal.parsing.Trees
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.io.AbsolutePath
import scala.meta.trees.Origin

class IndexedSymbols(
    isStatisticsEnabled: Boolean,
    trees: Trees,
    buffers: Buffers,
    buildTargets: BuildTargets,
    mtags: () => Mtags,
)(implicit rc: ReportContext) {
  // Used for workspace, is eager
  private val workspaceCache = TrieMap.empty[
    AbsolutePath,
    AllSymbols,
  ]

  type TopLevel = SymbolDefinition
  type ToplevelSymbol = String
  type AllSymbols = Array[TreeViewSymbolInformation]

  private val filteredSymbols: Set[SymbolInformation.Kind] = Set(
    SymbolInformation.Kind.CONSTRUCTOR,
    SymbolInformation.Kind.PARAMETER,
    SymbolInformation.Kind.TYPE_PARAMETER,
  )

  /* Used for dependencies lazily calculates all symbols in a jar.
   * At the start it only contains the definition of a toplevel Symbol, later
   * resolves to information about all symbols contained in the top level.
   */
  private val jarCache = TrieMap.empty[
    AbsolutePath,
    TrieMap[ToplevelSymbol, Either[TopLevel, AllSymbols]],
  ]

  def onChange(in: AbsolutePath): Unit = {
    workspaceCache.remove(in)
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

  private def scalaSymbols(in: AbsolutePath): Seq[SymbolInformation] = {
    trees
      .get(in)
      .map { tree =>
        ((tree, tree.origin) match {
          case (
                src: Source,
                parsed: Origin.Parsed,
              ) =>
            parsed.input match {
              case input: Input.VirtualFile =>
                val indexer =
                  new ScalaMtags(input, parsed.dialect, Some(src))
                Some(indexer.index().symbols)
              case _ => None
            }
          case _ => None
        }).getOrElse {
          // Trees don't return anything else than Source, and it should be impossible to get here
          scribe.error(
            s"[$in] Unexpected tree type ${tree.getClass} in IndexedSymbols with origin:\n${tree.origin} "
          )
          Seq.empty[SymbolInformation]
        }
      }
      .getOrElse(Seq.empty[SymbolInformation])
  }

  private def javaSymbols(in: AbsolutePath) = {
    val indexer = mtags().config.javaInstance(
      Input.VirtualFile(in.toString(), buffers.get(in).getOrElse(in.readText)),
      includeMembers = true,
    )
    indexer
      .index()
      .symbols
  }

  private def workspaceSymbolsFromPath(
      in: AbsolutePath
  ): Array[TreeViewSymbolInformation] = {
    val symbolInfos = if (in.isScala) { scalaSymbols(in) }
    else if (in.isJava && in.exists) { javaSymbols(in) }
    else List.empty[SymbolInformation]

    symbolInfos.collect {
      case info if !filteredSymbols(info.kind) =>
        TreeViewSymbolInformation(
          info.symbol,
          info.kind,
          info.properties,
        )
    }.toArray
  }

  /**
   * We load all symbols using the standard mtags indexing mechanisms
   *
   * @param in the input file to get symbols for
   * @param symbol we are looking for
   * @return list of tree view symbols within the file
   */
  def workspaceSymbols(
      in: AbsolutePath,
      symbol: String,
  ): Iterator[TreeViewSymbolInformation] = withTimer(s"$in") {
    val syms = workspaceCache.getOrElseUpdate(
      in,
      workspaceSymbolsFromPath(in),
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
    lazy val potentialSourceJar = buildTargets.sourceJarFor(in)
    if (!in.isSourcesJar && potentialSourceJar.isEmpty) {
      Iterator.empty[TreeViewSymbolInformation]
    } else {
      val realIn =
        if (!in.isSourcesJar && potentialSourceJar.isDefined)
          potentialSourceJar.get
        else in
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
      // root package doesn't need to calculate any members, they will be calculated lazily
      if (parsedSymbol.isRootPackage) {
        jarSymbols.values
          .collect {
            case Left(defn) =>
              Array(toTreeView(defn))
            case Right(list) => list
          }
          .flatten
          .iterator
      } // If it's a package we'll collect all the children
      else if (parsedSymbol.isPackage) {
        jarSymbols
          .collect {
            /* If the package we are looking for is the parent of the current symbol we
             * need to check if we have grandchildren and the nodes are exapandable
             * on the UI
             */
            case (_, Left(toplevel))
                if (toplevel.definitionSymbol.owner == parsedSymbol) =>
              val children =
                members(toplevel.path, dialect).map(toTreeView)
              jarSymbols.put(
                toplevel.definitionSymbol.value,
                Right(children),
              )
              children

            /* If this is further down we don't need to resolve it yet as
             * as we will check that later when resolving parent package
             */
            case (toplevelSymbol, Left(toplevel))
                if toplevelSymbol.startsWith(symbol) =>
              Array(toTreeView(toplevel))

            // If it's already calculated then we can just return it
            case (toplevelSymbol, Right(allSymbols))
                if toplevelSymbol.startsWith(symbol) =>
              allSymbols
            case _ => Array.empty[TreeViewSymbolInformation]
          }
          .flatten
          .iterator
      } else { // if we are looking for a particular symbol then we need to resolve it properly
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

  private def members(
      path: AbsolutePath,
      dialect: Dialect,
  ): Array[SymbolDefinition] = {
    symbolsAt(path, dialect)
      .filter(defn =>
        defn.kind.isEmpty || !defn.kind
          .exists(kind => kind.isParameter || kind.isTypeParameter)
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
              (source, mtags().toplevels(source, dialect).symbols)
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
      List((path, mtags().toplevels(path, dialect).symbols))
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
    val document = mtags().allSymbols(path, dialect)
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
