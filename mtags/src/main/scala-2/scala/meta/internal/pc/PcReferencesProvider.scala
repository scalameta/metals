package scala.meta.internal.pc

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.pc.OffsetParams
import scala.meta.pc.ReferencesRequest
import scala.meta.pc.VirtualFileParams

import org.eclipse.{lsp4j => l}

trait PcReferencesProvider {
  _: WithCompilationUnit with PcCollector[(String, Option[l.Range])] =>
  import compiler._
  protected def includeDefinition: Boolean
  protected def result(): List[(String, Option[l.Range])]
  override def allowZeroExtentImplicits = true

  def collect(
      parent: Option[Tree]
  )(
      tree: Tree,
      toAdjust: Position,
      sym: Option[Symbol]
  ): (String, Option[l.Range]) = {
    val (pos, _) = toAdjust.adjust(text)
    tree match {
      case t: DefTree if !includeDefinition =>
        (compiler.semanticdbSymbol(sym.getOrElse(t.symbol)), None)
      case t =>
        (
          compiler.semanticdbSymbol(sym.getOrElse(t.symbol)),
          Some(pos.toLsp)
        )
    }
  }

  def references(): List[PcReferencesResult] = {
    val res = result()
      .groupBy(_._1)
      .map { case (symbol, locs) =>
        PcReferencesResult(
          symbol,
          locs.flatMap { case (_, optRange) =>
            optRange.map(new l.Location(params.uri().toString(), _))
          }.asJava
        )
      }
      .toList
    cleanUp()
    res
  }
}

class LocalPcReferencesProvider(
    override val compiler: MetalsGlobal,
    params: OffsetParams,
    override val includeDefinition: Boolean
) extends WithSymbolSearchCollector[(String, Option[l.Range])](compiler, params)
    with PcReferencesProvider

class BySymbolPCReferencesProvider(
    override val compiler: MetalsGlobal,
    params: VirtualFileParams,
    override val includeDefinition: Boolean,
    semanticDbSymbols: Set[String]
) extends WithCompilationUnit(
      compiler,
      params,
      shouldRemoveCompilationUnitAfterUse = true
    )
    with PcCollector[(String, Option[l.Range])]
    with PcReferencesProvider {
  def result(): List[(String, Option[l.Range])] = {
    val sought = semanticDbSymbols
      .flatMap(compiler.compilerSymbol(_))
      .flatMap(symbolAlternatives(_))
    if (sought.isEmpty) Nil
    else resultWithSought(sought)
  }
}

object PcReferencesProvider {
  def apply(
      compiler: MetalsGlobal,
      params: ReferencesRequest
  ): PcReferencesProvider =
    if (params.offsetOrSymbol().isLeft()) {
      val offsetParams = CompilerOffsetParams(
        params.file().uri(),
        params.file().text(),
        params.offsetOrSymbol().getLeft()
      )
      new LocalPcReferencesProvider(
        compiler,
        offsetParams,
        params.includeDefinition()
      )
    } else
      new BySymbolPCReferencesProvider(
        compiler,
        params.file(),
        params.includeDefinition(),
        params.alternativeSymbols().asScala.toSet + params
          .offsetOrSymbol()
          .getRight()
      )
}
