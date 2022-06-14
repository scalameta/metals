package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.Enumerator
import scala.meta.Pat
import scala.meta.Term
import scala.meta.Tree
import scala.meta.inputs.Position
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.CodeAction
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.parsing.Trees
import scala.meta.io.AbsolutePath
import scala.meta.pc.CancelToken

import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.{lsp4j => l}

class FlatMapToForComprehensionCodeAction(
    trees: Trees,
    buffers: Buffers
) extends CodeAction {
  override def kind: String = l.CodeActionKind.RefactorRewrite

  override def contribute(params: CodeActionParams, token: CancelToken)(implicit
      ec: ExecutionContext
  ): Future[Seq[l.CodeAction]] = Future {
    val uri = params.getTextDocument().getUri()

    val path = uri.toAbsolutePath
    val range = params.getRange()
    val maybeTree =
      if (range.getStart == range.getEnd)
        trees
          .findLastEnclosingAt[Term.Apply](
            path,
            range.getStart(),
            {
              case _: Term.Apply => true
              case _ => false
            }
          )
      else
        None

    val maybeChainedCodeAction = for {
      document <- buffers.get(path)
      termApply <- maybeTree
      termApplyArg <- termApply.args.headOption
      indentation = getIndentForPos(termApplyArg.pos, document)
    } yield codeActionWithApply(
      path,
      termApply,
      indentation
    )
    maybeChainedCodeAction.flatten.toSeq

  }

  private def constructCodeAction(
      forElementsList: List[Enumerator],
      yieldTerm: Term,
      indentation: String,
      path: AbsolutePath,
      startPos: l.Position,
      endPos: l.Position
  ): l.CodeAction = {

    val indentedElems = forElementsList
      .map(
        _.syntax
          .split(Array('\n'))
          .map(line => s"$indentation   $line")
          .mkString("\n")
      )
      .mkString("\n")

    val yieldTermIndentedString = yieldTerm.syntax
      .split(Array('\n'))
      .map(line => s"$indentation   $line")
      .mkString("\n")

    val forYieldString =
      s"""|{
          |$indentation for {
          |$indentedElems
          |$indentation }  yield {
          |$yieldTermIndentedString
          |$indentation }
          |$indentation}""".stripMargin

    val codeAction = new l.CodeAction()
    val range =
      new l.Range(startPos, endPos)
    codeAction.setTitle(
      FlatMapToForComprehensionCodeAction.flatMapToForComprehension
    )
    codeAction.setKind(this.kind)
    val forComprehensionTextEdit = new l.TextEdit(range, forYieldString)
    codeAction.setEdit(
      new l.WorkspaceEdit(
        Map(
          path.toURI.toString -> List(forComprehensionTextEdit).asJava
        ).asJava
      )
    )
    codeAction
  }

  private def findTopMostApply(
      currentTermApply: Term.Apply,
      lastValidTermApply: Option[Term.Apply]
  ): Option[Term.Apply] = {

    currentTermApply.fun match {
      case term if term.isNot[Term.Select] => lastValidTermApply
      case termSelect: Term.Select
          if termSelect.name.value != "map" && termSelect.name.value != "flatMap" &&
            termSelect.name.value != "filter" && termSelect.name.value != "withFilter" && termSelect.name.value != "filterNot" =>
        lastValidTermApply
      case _: Term.Select =>
        if (
          !currentTermApply.parent.exists(parent =>
            parent.is[Term.Select] && parent.parent.exists(_.is[Term.Apply])
          )
        ) {
          Some(currentTermApply)
        } else {
          val result = Some(currentTermApply)
          val grandParentApply =
            currentTermApply.parent.get.parent.get.asInstanceOf[Term.Apply]
          findTopMostApply(grandParentApply, result)
        }
    }
  }

  private def codeActionWithApply(
      path: AbsolutePath,
      termApply: Term.Apply,
      indentation: String
  ): Option[l.CodeAction] = {

    findTopMostApply(termApply, None).flatMap { topMostApply =>
      val nameGenerator = MetalsNames(topMostApply, "generatedByMetals")
      val (forElements, maybeYieldTerm) =
        extractChainedForYield(
          None,
          None,
          List.empty,
          topMostApply,
          nameGenerator
        )

      if (forElements.nonEmpty) {
        maybeYieldTerm.map { yieldTerm =>
          constructCodeAction(
            forElements,
            yieldTerm,
            indentation,
            path,
            topMostApply.pos.toLSP.getStart,
            topMostApply.pos.toLSP.getEnd
          )
        }
      } else None
    }

  }

  private def replacePlaceHolder(
      tree: Term,
      newName: Term.Name,
      allowedToGetInsideApply: Boolean
  ): (Term, Int) =
    tree match {
      case Term.Apply(fun, args) if allowedToGetInsideApply =>
        val (newFun, funReplacementTimes) =
          replacePlaceHolder(fun, newName, allowedToGetInsideApply)
        val (newArgs, argsReplacementTimes) =
          args.map(replacePlaceHolder(_, newName, false)).unzip

        val replacementTimes =
          argsReplacementTimes.fold(funReplacementTimes)((result, newElem) =>
            result + newElem
          )

        (Term.Apply(newFun, newArgs), replacementTimes)

      case Term.Eta(expr) if allowedToGetInsideApply =>
        (Term.Apply(expr, List(newName)), 1)

      case Term.ApplyUnary(op, arg) if allowedToGetInsideApply =>
        val (newArg, argReplacementTimes) =
          replacePlaceHolder(arg, newName, allowedToGetInsideApply)
        (Term.ApplyUnary(op, newArg), argReplacementTimes)

      case Term.ApplyUsing(fun, args) if allowedToGetInsideApply =>
        val (newFun, funReplacementTimes) =
          replacePlaceHolder(fun, newName, allowedToGetInsideApply)
        val (newArgs, argsReplacementTimes) =
          args.map(replacePlaceHolder(_, newName, false)).unzip

        val replacementTimes =
          argsReplacementTimes.fold(funReplacementTimes)((result, newElem) =>
            result + newElem
          )

        (Term.ApplyUsing(newFun, newArgs), replacementTimes)

      case Term.ApplyInfix(lhs, op, targs, args) if allowedToGetInsideApply =>
        val (newLHS, lhsReplacementTimes) =
          replacePlaceHolder(lhs, newName, allowedToGetInsideApply)
        val (newArgs, argsReplacementTimes) =
          args
            .map(replacePlaceHolder(_, newName, allowedToGetInsideApply))
            .unzip

        val replacementTimes =
          argsReplacementTimes.fold(lhsReplacementTimes)((result, newElem) =>
            result + newElem
          )

        (Term.ApplyInfix(newLHS, op, targs, newArgs), replacementTimes)

      case Term.Select(qual, name) =>
        val (newQual, qualReplacementTimes) =
          replacePlaceHolder(qual, newName, allowedToGetInsideApply)
        (
          Term.Select(
            newQual,
            name
          ),
          qualReplacementTimes
        )
      case Term.Placeholder() => (newName, 1)
      case other => (other, 0)
    }

  private def replacePlaceHolderInTermWithNewName(
      term: Term,
      nameGenerator: MetalsNames
  ): Option[(String, Term)] = {
    val newName =
      nameGenerator.createNewName()

    val (newTerm, replacementTimes) =
      replacePlaceHolder(term, Term.Name(newName), true)
    (
      if (replacementTimes == 1) Some((newName, newTerm))
      else if (replacementTimes == 0)
        Some((newName), Term.Apply(newTerm, List(Term.Name(newName))))
      else None
    )
  }

  private def processValueNameAndNextQual(
      tree: Tree,
      nameGenerator: MetalsNames
  ): Option[(String, Term)] = {
    tree match {
      case Term.Function(List(param), term) if param.name.value.isEmpty =>
        val newName =
          nameGenerator.createNewName()
        Some((newName, term))
      case Term.Function(List(param), term) =>
        (Some((param.name.value, term)))

      case Term.AnonymousFunction(term) =>
        replacePlaceHolderInTermWithNewName(term, nameGenerator)
      case term: Term
          if term.is[Term.Apply] || term.is[Term.Eta] || term
            .is[Term.ApplyUnary] || term.is[Term.ApplyUsing] || term
            .is[Term.ApplyInfix] || term.is[Term.Name] || term
            .is[Term.Select] =>
        replacePlaceHolderInTermWithNewName(term, nameGenerator)
      case Term.Block(List(function)) =>
        processValueNameAndNextQual(function, nameGenerator)
      case _ =>
        None

    }
  }

  private def obtainNextYieldAndElemsForMap(
      nameGenerator: MetalsNames,
      perhapsLastName: Option[String],
      shouldFlat: Boolean,
      existingForElements: List[Enumerator],
      currentYieldTerm: Option[Term],
      nextQual: Term
  ): (List[Enumerator], Option[Term]) = {
    perhapsLastName match {
      case Some(lastName) =>
        (
          List(
            if (shouldFlat)
              Enumerator.Generator.apply(
                Pat.Var.apply(Term.Name.apply(lastName)),
                nextQual
              )
            else
              Enumerator.Val.apply(
                Pat.Var.apply(Term.Name.apply(lastName)),
                nextQual
              )
          ) ++ existingForElements,
          currentYieldTerm
        )
      case None =>
        if (shouldFlat) {
          val lastGeneratedName =
            nameGenerator.createNewName()
          val newEnumerations = List(
            Enumerator.Generator(
              Pat.Var.apply(Term.Name.apply(lastGeneratedName)),
              nextQual
            )
          )
          val newYield = Term.Name.apply(lastGeneratedName)
          (newEnumerations, Some(newYield))
        } else (existingForElements, Some(nextQual))
    }
  }

  private def processMap(
      elems: List[Enumerator],
      maybeYieldTerm: Option[Term],
      nameGenerator: MetalsNames,
      valueName: String,
      termSelectQual: Term
  ): (List[Enumerator], Option[Term]) = {

    termSelectQual match {
      case qualTermApply: Term.Apply =>
        extractChainedForYield(
          Some(valueName),
          maybeYieldTerm,
          elems,
          qualTermApply,
          nameGenerator
        )
      case otherQual =>
        (
          Enumerator.Generator(
            Pat.Var.apply(Term.Name.apply(valueName)),
            otherQual
          )
            +: elems,
          maybeYieldTerm
        )

    }

  }

  private def processFilter(
      perhapsValueNameAndNextQual: Option[(String, Term)],
      nameGenerator: MetalsNames,
      perhapsLastName: Option[String],
      isFilter: Boolean,
      existingForElements: List[Enumerator],
      currentYieldTerm: Option[Term],
      termSelectQual: Term
  ): (List[Enumerator], Option[Term]) = {

    val result = for {
      valueName <- perhapsValueNameAndNextQual.map(_._1)
      nextCondition <- perhapsValueNameAndNextQual.map(_._2)
    } yield {
      val (elems, maybeYieldTerm): (List[Enumerator], Option[Term]) =
        perhapsLastName match {
          case Some(lastName) =>
            (
              Enumerator.Val.apply(
                Pat.Var.apply(Term.Name.apply(lastName)),
                Term.Name.apply(valueName)
              ) +: existingForElements,
              currentYieldTerm
            )
          case None =>
            (existingForElements, Some(Term.Name.apply(valueName)))
        }

      termSelectQual match {
        case qualTermApply: Term.Apply =>
          extractChainedForYield(
            Some(valueName),
            maybeYieldTerm,
            Enumerator.Guard.apply(
              if (isFilter) nextCondition
              else
                Term.ApplyUnary.apply(Term.Name.apply("!"), nextCondition)
            ) +: elems,
            qualTermApply,
            nameGenerator
          )
        case otherQual =>
          (
            Enumerator.Generator(
              Pat.Var.apply(Term.Name.apply(valueName)),
              otherQual
            )
              +: Enumerator.Guard.apply(
                if (isFilter) nextCondition
                else
                  Term.ApplyUnary.apply(Term.Name.apply("!"), nextCondition)
              ) +: elems,
            maybeYieldTerm
          )
      }
    }
    result.getOrElse(List.empty, currentYieldTerm)
  }

  private def extractChainedForYield(
      perhapsLastName: Option[String],
      currentYieldTerm: Option[Term],
      existingForElements: List[Enumerator],
      termApply: Term.Apply,
      nameGenerator: MetalsNames
  ): (List[Enumerator], Option[Term]) = {
    val perhapsValueNameAndNextQual =
      processValueNameAndNextQual(
        termApply.args.head,
        nameGenerator
      )

    termApply.fun match {
      case termSelect: Term.Select
          if termSelect.name.value == "flatMap" || termSelect.name.value == "map" =>
        val shouldFlat = termSelect.name.value == "flatMap"

        val result = for {
          valueName <- perhapsValueNameAndNextQual.map(_._1)
          nextQual <- perhapsValueNameAndNextQual.map(_._2)
        } yield {
          val (elems, maybeYieldTerm) =
            obtainNextYieldAndElemsForMap(
              nameGenerator,
              perhapsLastName,
              shouldFlat,
              existingForElements,
              currentYieldTerm,
              nextQual
            )

          processMap(
            elems,
            maybeYieldTerm,
            nameGenerator,
            valueName,
            termSelect.qual
          )
        }
        result.getOrElse(List.empty, None)

      case termSelect: Term.Select
          if termSelect.name.value == "filter" || termSelect.name.value == "filterNot" ||
            termSelect.name.value == "withFilter" =>
        val isFilter =
          termSelect.name.value == "filter" || termSelect.name.value == "withFilter"
        processFilter(
          perhapsValueNameAndNextQual,
          nameGenerator,
          perhapsLastName,
          isFilter,
          existingForElements,
          currentYieldTerm,
          termSelect.qual
        )

      case _ =>
        perhapsLastName match {
          case Some(lastName) =>
            (
              Enumerator.Generator(
                Pat.Var.apply(Term.Name.apply(lastName)),
                termApply
              )
                +: existingForElements,
              currentYieldTerm
            )
          case None =>
            (existingForElements, Some(termApply))
        }
    }
  }

  private def getIndentForPos(
      treePos: Position,
      document: String
  ): String =
    document
      .substring(treePos.start - treePos.startColumn, treePos.start)
      .takeWhile(_.isWhitespace)

}

object FlatMapToForComprehensionCodeAction {
  val flatMapToForComprehension = "Turn into for comprehension"
}
