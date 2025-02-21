package scala.meta.internal.pc

import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.internal.pc.IndexedContext.Result
import scala.meta.internal.pc.InlineValueProvider.Errors
import scala.meta.pc.OffsetParams

import dotty.tools.dotc.ast.NavigateAST
import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags.*
import dotty.tools.dotc.core.StdNames
import dotty.tools.dotc.core.Symbols.Symbol
import dotty.tools.dotc.interactive.Interactive
import dotty.tools.dotc.interactive.InteractiveDriver
import dotty.tools.dotc.util.SourcePosition
import org.eclipse.lsp4j as l

final class PcInlineValueProviderImpl(
    driver: InteractiveDriver,
    val params: OffsetParams,
) extends WithSymbolSearchCollector[Option[Occurence]](driver, params)
    with InlineValueProvider:

  val position: l.Position = pos.toLsp.getStart()

  override def collect(parent: Option[Tree])(
      tree: Tree | EndMarker,
      pos: SourcePosition,
      sym: Option[Symbol],
  ): Option[Occurence] =
    val (adjustedPos, _) = pos.adjust(text)
    tree match
      case tree: Tree => Some(Occurence(tree, parent, adjustedPos))
      case _ => None

  override def defAndRefs(): Either[String, (Definition, List[Reference])] =
    val newctx = driver.currentCtx.fresh.setCompilationUnit(unit)
    val allOccurences = result().flatten
    for
      definition <- allOccurences
        .collectFirst { case Occurence(defn: ValDef, _, pos) =>
          DefinitionTree(defn, pos)
        }
        .toRight(Errors.didNotFindDefinition)
      path = Interactive.pathTo(unit.tpdTree, definition.tree.rhs.span)(using newctx)
      indexedContext = IndexedContext(Interactive.contextOfPath(path)(using newctx))
      symbols = symbolsUsedInDefn(definition.tree.rhs).filter(indexedContext.lookupSym(_) == Result.InScope)
      references <- getReferencesToInline(definition, allOccurences, symbols)
    yield
      val (deleteDefinition, refsEdits) = references

      val defPos = definition.tree.sourcePos
      val defEdit = Definition(
        defPos.toLsp,
        adjustRhs(definition.tree.rhs.sourcePos),
        RangeOffset(defPos.start, defPos.end),
        definitionRequiresBrackets(definition.tree.rhs)(using newctx),
        deleteDefinition,
      )

      (defEdit, refsEdits)
    end for
  end defAndRefs

  extension (blck: untpd.Block)
    def isIndentationSensitive: Boolean =
      val untpd.Block(stats, expr) = blck
      (stats :+ expr).head.span != blck.span

  private def definitionRequiresBrackets(tree: Tree)(using Context): RequiresBrackets =
    NavigateAST
      .untypedPath(tree.span)
      .headOption
      .map {
        case _: untpd.If => Yes
        case _: untpd.Function => Yes
        case _: untpd.Match => Yes
        case _: untpd.ForYield => Yes
        case _: untpd.InfixOp => Yes
        case _: untpd.ParsedTry => Yes
        case _: untpd.Try => Yes
        case blck: untpd.Block =>
          if blck.isIndentationSensitive then VeryMuch else Yes
        case _: untpd.Typed => Yes
        case _ => No
      }
      .getOrElse(No)

  end definitionRequiresBrackets

  private def referenceRequiresBrackets(tree: Tree)(using Context): Boolean =
    NavigateAST.untypedPath(tree.span) match
      case (_: untpd.InfixOp) :: _ => true
      case _ =>
        tree match
          case _: Apply => StdNames.nme.raw.isUnary(tree.symbol.name)
          case _: Select => true
          case _: Ident => true
          case _ => false

  end referenceRequiresBrackets
  // format: on

  private def adjustRhs(pos: SourcePosition) =
    def extend(point: Int, acceptedChar: Char, step: Int): Int =
      val newPoint = point + step
      if newPoint > 0 && newPoint < text.length && text(
          newPoint
        ) == acceptedChar
      then extend(newPoint, acceptedChar, step)
      else point
    val adjustedStart = extend(pos.start, '(', -1)
    val adjustedEnd = extend(pos.end - 1, ')', 1) + 1
    text.slice(adjustedStart, adjustedEnd).mkString

  /** 
   * Return all scope symbols used in this
   */
  private def symbolsUsedInDefn(rhs: Tree): Set[Symbol] =
    def collectNames(
        symbols: Set[Symbol],
        tree: Tree,
    ): Set[Symbol] =
      tree match
        case id: (Ident | Select)
            if !id.symbol.is(Synthetic) && !id.symbol.is(Implicit) =>
          symbols + tree.symbol
        case _ => symbols

    val traverser = new DeepFolder[Set[Symbol]](collectNames)
    traverser(Set(), rhs)
  end symbolsUsedInDefn

  private def getReferencesToInline(
      definition: DefinitionTree,
      allOccurences: List[Occurence],
      symbols: Set[Symbol],
  ): Either[String, (Boolean, List[Reference])] =
    val defIsLocal = definition.tree.symbol.ownersIterator
      .drop(1)
      .exists(e => e.isTerm)
    def allreferences = allOccurences.filterNot(_.isDefn)
    def inlineAll() =
      makeRefsEdits(allreferences, symbols).map((true, _))
    if definition.tree.sourcePos.toLsp.encloses(position)
    then if defIsLocal then inlineAll() else Left(Errors.notLocal)
    else
      allreferences match
        case ref :: Nil if defIsLocal => inlineAll()
        case list =>
          for
            ref <- list
              .find(_.pos.toLsp.encloses(position))
              .toRight(Errors.didNotFindReference)
            refEdits <- makeRefsEdits(List(ref), symbols)
          yield (false, refEdits)
    end if
  end getReferencesToInline

  private def makeRefsEdits(
      refs: List[Occurence],
      symbols: Set[Symbol],
  ): Either[String, List[Reference]] =
    val newctx = driver.currentCtx.fresh.setCompilationUnit(unit)
    def buildRef(occurence: Occurence): Either[String, Reference] =
      val path =
        Interactive.pathTo(unit.tpdTree, occurence.pos.span)(using newctx)
      val indexedContext = IndexedContext(
        Interactive.contextOfPath(path)(using newctx)
      )
      import indexedContext.ctx
      val conflictingSymbols = symbols
        .withFilter {
          indexedContext.lookupSym(_) match
            case IndexedContext.Result.Conflict => true
            case _ => false
        }
        .map(_.fullNameBackticked)
      if conflictingSymbols.isEmpty then
        Right(
          Reference(
            occurence.pos.toLsp,
            occurence.parent.map(p =>
              RangeOffset(p.sourcePos.start, p.sourcePos.end)
            ),
            occurence.parent
              .map(p => referenceRequiresBrackets(p)(using newctx))
              .getOrElse(false),
          )
        )
      else Left(Errors.variablesAreShadowed(conflictingSymbols.mkString(", ")))
    end buildRef
    refs.foldLeft((Right(List())): Either[String, List[Reference]])((acc, r) =>
      for
        collectedEdits <- acc
        currentEdit <- buildRef(r)
      yield currentEdit :: collectedEdits
    )
  end makeRefsEdits

end PcInlineValueProviderImpl

case class Occurence(tree: Tree, parent: Option[Tree], pos: SourcePosition):
  def isDefn =
    tree match
      case _: ValDef => true
      case _ => false

case class DefinitionTree(tree: ValDef, pos: SourcePosition)
