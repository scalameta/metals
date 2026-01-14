package scala.meta.internal.pc

import scala.annotation.tailrec

import scala.meta._
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.pc.InlineValueProvider.Errors
import scala.meta.pc.OffsetParams

import org.eclipse.{lsp4j => l}

final class PcInlineValueProviderImpl(
    val cp: MetalsGlobal,
    val params: OffsetParams
) extends InlineValueProvider {
  import cp._

  val pcCollector: WithSymbolSearchCollector[Occurence] =
    new WithSymbolSearchCollector[Occurence](cp, params) {
      def collect(
          parent: Option[compiler.Tree]
      )(
          tree: compiler.Tree,
          pos: Position,
          sym: Option[compiler.Symbol]
      ): Occurence = {
        val (adjustedPos, _) = pos.adjust(this.text)
        Occurence(
          tree.asInstanceOf[Tree],
          parent.map(_.asInstanceOf[Tree]),
          adjustedPos
        )
      }
    }

  val position: l.Position = pcCollector.pos.toLsp.getStart()

  override val text: Array[Char] = pcCollector.text

  override def defAndRefs(): Either[String, (Definition, List[Reference])] = {
    val allOccurences = pcCollector.result()
    for {
      definition <- allOccurences
        .collectFirst { case Occurence(defn: ValDef, _, pos) =>
          DefinitionTree(defn, pos)
        }
        .toRight(Errors.didNotFindDefinition)
        .right
      references <- referenceEdits(definition, allOccurences).right
    } yield {
      val (deleteDefinition, refsEdits) = references

      val defRhsPos = definition.tree.rhs.pos
      val defPos = definition.tree.pos
      val rhsSourceString = text.slice(defRhsPos.start, defRhsPos.end).mkString
      val defEdit = Definition(
        defPos.toLsp,
        rhsSourceString,
        RangeOffset(defPos.start, defPos.end),
        definitionNeedsBrackets(rhsSourceString),
        definitionNeedsCurlyBraces(rhsSourceString),
        deleteDefinition
      )

      (defEdit, refsEdits)
    }
  }

  private def symbolsUsedInDef(rhs: Tree): List[Symbol] = {
    def validateSymbol(symbol: Symbol) =
      !symbol.isSynthetic && !symbol.isImplicit

    def isLocalToRhs(symbol: Symbol): Boolean = {
      // Check if the symbol is defined within the RHS expression itself
      // (e.g., lambda parameters, local vals in blocks)
      // Such symbols should not be checked for shadowing since they are
      // locally bound and won't be affected by external shadowing
      symbol.pos.isDefined && rhs.pos.isDefined &&
      symbol.pos.start >= rhs.pos.start && symbol.pos.end <= rhs.pos.end
    }

    @tailrec
    def collectNames(
        symbols: List[Symbol],
        toTraverse: List[Tree]
    ): List[Symbol] =
      toTraverse match {
        case tree :: toTraverse => {
          val nextSymbols =
            tree match {
              case id: Ident
                  if validateSymbol(id.symbol) && !isLocalToRhs(id.symbol) =>
                id.symbol :: symbols
              case s: Select
                  if validateSymbol(s.symbol) && !isLocalToRhs(s.symbol) =>
                s.symbol :: symbols
              case _ => symbols
            }
          collectNames(nextSymbols, toTraverse ++ tree.children)
        }
        case Nil => symbols
      }
    collectNames(List(), List(rhs))
  }

  private def referenceEdits(
      definition: DefinitionTree,
      allOccurences: List[Occurence]
  ): Either[String, (Boolean, List[Reference])] = {
    val defIsLocal = definition.tree.symbol.ownersIterator
      .drop(1)
      .exists(e => e.isTerm)
    val allreferences = allOccurences.filterNot(_.isDefn)
    val importContext = doLocateContext(definition.tree.rhs.pos)
    val symbols = symbolsUsedInDef(definition.tree.rhs).distinct.filter { sym =>
      importContext.symbolIsInScope(sym)
    }
    def inlineAll() =
      makeRefs(allreferences, symbols).right
        .map((true, _))
    if (definition.pos.toLsp.encloses(position))
      if (defIsLocal) inlineAll()
      else Left(Errors.notLocal)
    else
      allreferences match {
        case _ :: Nil if defIsLocal => inlineAll()
        case list =>
          for {
            ref <- list
              .find(_.pos.toLsp.encloses(position))
              .toRight(Errors.didNotFindReference)
              .right
            edits <- makeRefs(List(ref), symbols).right
          } yield (false, edits)
      }
  }

  private def makeRefs(
      refs: List[Occurence],
      symbols: List[Symbol]
  ): Either[String, List[Reference]] = {
    def buildRef(ref: Occurence): Either[String, Reference] = {
      val importContext: Context = doLocateContext(ref.pos)
      val conflicts = symbols
        .collect {
          case sym
              if (!importContext.symbolIsInScope(sym)
                && importContext.nameIsInScope(sym.name)) =>
            sym.fullNameSyntax
        }
      if (conflicts.isEmpty) {
        val parentPos = ref.parent.map(p => RangeOffset(p.pos.start, p.pos.end))
        Right(
          Reference(
            ref.pos.toLsp,
            parentPos,
            referenceNeedsBrackets(parentPos),
            ref.pos.start > 0 && text(ref.pos.start - 1) == '$'
          )
        )
      } else Left(Errors.variablesAreShadowed(conflicts.mkString(", ")))
    }

    refs.foldLeft((Right(List())): Either[String, List[Reference]])(
      (acc, ref) =>
        for {
          collectedRefs <- acc.right
          currentRef <- buildRef(ref).right
        } yield currentRef :: collectedRefs
    )
  }

  def definitionNeedsBrackets(rhs: String): Boolean =
    rhs.parse[Term].toOption match {
      case Some(_: Term.ApplyInfix) => true
      case Some(_: Term.Function) => true
      case Some(_: Term.ForYield) => true
      case Some(_: Term.PartialFunction) => true
      case Some(_: Term.PolyFunction) => true
      case Some(_: Term.AnonymousFunction) => true
      case Some(_: Term.Do) => true
      case Some(_: Term.While) => true
      case _ => false
    }

  def referenceNeedsBrackets(
      parentPos: Option[RangeOffset]
  ): Boolean = {
    parentPos.flatMap(t =>
      text.slice(t.start, t.end).parse[Term].toOption
    ) match {
      case Some(_: Term.ApplyInfix) => true
      case Some(_: Term.ApplyUnary) => true
      case Some(_: Term.Select) => true
      case Some(_: Term.Name) => true // apply
      case _ => false
    }
  }

  def definitionNeedsCurlyBraces(rhs: String): Boolean =
    !rhs.trim.parse[Term].toOption.exists(_.is[Term.Name])

  case class Occurence(tree: Tree, parent: Option[Tree], pos: Position) {
    def isDefn: Boolean =
      tree match {
        case _: ValDef => true
        case _ => false
      }
  }

  case class DefinitionTree(tree: ValDef, pos: Position)

}
