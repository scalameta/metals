package scala.meta.internal.pc

import scala.annotation.tailrec

import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.pc.InlineValueProvider.Errors
import scala.meta.pc.OffsetParams

import org.eclipse.{lsp4j => l}

final class PcInlineValueProviderImpl(
    val compiler: MetalsGlobal,
    val params: OffsetParams
) extends InlineValueProvider {
  import compiler._

  val pcCollector: PcCollector[Occurence] =
    new PcCollector[Occurence](compiler, params) {
      def collect(
          parent: Option[compiler.Tree]
      )(
          tree: compiler.Tree,
          pos: Position,
          sym: Option[compiler.Symbol]
      ): Occurence = {
        val (adjustedPos, _) = adjust(pos)
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
      val defEdit = Definition(
        defPos.toLsp,
        text.slice(defRhsPos.start, defRhsPos.end).mkString,
        RangeOffset(defPos.start, defPos.end),
        deleteDefinition
      )

      (defEdit, refsEdits)
    }
  }

  private def symbolsUsedInDef(
      rhs: Tree
  ): List[Symbol] = {
    @tailrec
    def collectNames(
        symbols: List[Tree],
        toTraverse: List[Tree]
    ): List[Tree] =
      toTraverse match {
        case tree :: toTraverse => {
          val nextSymbols =
            tree match {
              case id: Ident
                  if !id.symbol.isSynthetic && !id.symbol.isImplicit =>
                id :: symbols
              case s: Select if !s.symbol.isSynthetic && !s.symbol.isImplicit =>
                s :: symbols
              case _ => symbols
            }
          collectNames(nextSymbols, toTraverse ++ tree.children)
        }
        case Nil => symbols
      }
    collectNames(List(), List(rhs)).map(_.symbol)
  }

  private def referenceEdits(
      definition: DefinitionTree,
      allOccurences: List[Occurence]
  ): Either[String, (Boolean, List[Reference])] = {
    val defIsLocal = definition.tree.symbol.ownersIterator
      .drop(1)
      .exists(e => e.isTerm)
    val allreferences = allOccurences.filterNot(_.isDefn)
    val symbols = symbolsUsedInDef(definition.tree.rhs)
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
      if (conflicts.isEmpty)
        Right(
          Reference(
            ref.pos.toLsp,
            ref.parent.map(p => RangeOffset(p.pos.start, p.pos.end))
          )
        )
      else Left(Errors.variablesAreShadowed(conflicts.mkString(", ")))
    }

    refs.foldLeft((Right(List())): Either[String, List[Reference]])(
      (acc, ref) =>
        for {
          collectedRefs <- acc.right
          currentRef <- buildRef(ref).right
        } yield currentRef :: collectedRefs
    )
  }

  case class Occurence(tree: Tree, parent: Option[Tree], pos: Position) {
    def isDefn: Boolean =
      tree match {
        case _: ValDef => true
        case _ => false
      }
  }

  case class DefinitionTree(tree: ValDef, pos: Position)

}
