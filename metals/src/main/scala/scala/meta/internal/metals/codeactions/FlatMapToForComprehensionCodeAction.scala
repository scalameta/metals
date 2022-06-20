package scala.meta.internal.metals.codeactions

import scala.annotation.tailrec
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
            range.getStart()
          )
      else
        None

    val maybeChainedCodeAction = for {
      document <- buffers.get(path)
      termApply <- maybeTree
      termApplyArg <- termApply.args.headOption
      // the termApply itself might be too outdented in the first line.
      // the position of the arg which falls on the subsequent lines
      // gives the right indentation
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
          |$indentation } yield {
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

  /**
   * This method traverses the apply chain, from where the cursor is,
   *  to the outermost [[Term.Apply]] which has one of the functions of
   *  `map`, `flatMap`, `filter`, `filterNot`, or `withFilter`
   *  and returns that.
   *
   * As an example,
   * {{{
   * List(1, 2, 3)
   *   .m<<>>ap(x => x)
   *  .flatMap(Some(_))
   *  .filter(_ > 3)
   * }}}
   * <p>Now when the cursor is on `map`, we want to start the conversion
   * on `filter`` instead, which is the parentMost or `outerMost` apply.
   * @param currentTermApply the termApply on which the cursor is
   *                         when invoking the code action
   * @param lastValidTermApply the last inner [[Term.Apply]] from the previous
   *                           iteration which had one of the functions of
   *                           `map`, `flatMap`, `filter`, `filterNot`, or `withFilter`
   *                           in its [[Term.Select]]
   * @return the `lastValidTermApply`` if the `currenTermApply` does not have
   *         an interesting function. Otherwise, the currentTermApply.
   */
  @tailrec
  private def findOuterMostApply(
      currentTermApply: Term.Apply,
      lastValidTermApply: Option[Term.Apply]
  ): Option[Term.Apply] = {

    val interestingSelects =
      Set("map", "flatMap", "filter", "filterNot", "withFilter")

    currentTermApply.fun match {
      case term if term.isNot[Term.Select] => lastValidTermApply
      case termSelect: Term.Select
          if !interestingSelects.contains(termSelect.name.value) =>
        lastValidTermApply
      case _: Term.Select =>
        currentTermApply.parent.flatMap(_.parent) match {
          case Some(next @ Term.Apply(_: Term.Select, _)) =>
            findOuterMostApply(next, Some(currentTermApply))
          case _ => Some(currentTermApply)
        }
    }
  }

  private def codeActionWithApply(
      path: AbsolutePath,
      termApply: Term.Apply,
      indentation: String
  ): Option[l.CodeAction] = {

    findOuterMostApply(termApply, None).flatMap { outerMostApply =>
      val nameGenerator = MetalsNames(outerMostApply, "generatedByMetals")
      val (forElements, maybeYieldTerm) =
        extractChainedForYield(
          None,
          None,
          List.empty,
          outerMostApply,
          nameGenerator
        )

      if (forElements.nonEmpty) {
        maybeYieldTerm.map { yieldTerm =>
          constructCodeAction(
            forElements,
            yieldTerm,
            indentation,
            path,
            outerMostApply.pos.toLSP.getStart,
            outerMostApply.pos.toLSP.getEnd
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
          argsReplacementTimes.sum + funReplacementTimes

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
          argsReplacementTimes.sum + funReplacementTimes

        (Term.ApplyUsing(newFun, newArgs), replacementTimes)

      case Term.ApplyInfix(lhs, op, targs, args) if allowedToGetInsideApply =>
        val (newLHS, lhsReplacementTimes) =
          replacePlaceHolder(lhs, newName, allowedToGetInsideApply)
        val (newArgs, argsReplacementTimes) =
          args
            .map(replacePlaceHolder(_, newName, allowedToGetInsideApply))
            .unzip

        val replacementTimes =
          lhsReplacementTimes + argsReplacementTimes.sum

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
      case term: Term // this is for cases when they are not wrapped inside
          // Anonymous function due to a bug, such as for .map(curried(7) _ )
          if term.is[Term.Apply] || term.is[Term.Eta]
            || term.is[Term.ApplyUnary] || term.is[Term.ApplyUsing]
            || term.is[Term.ApplyInfix] || term.is[Term.Name]
            || term.is[Term.Select] =>
        replacePlaceHolderInTermWithNewName(term, nameGenerator)
      case Term.Block(List(function)) =>
        processValueNameAndNextQual(function, nameGenerator)
      case _ =>
        None

    }
  }

  /**
   * connect what is passed as argument to map/flatMap
   * to the param name from the potential previous iteration and
   * conclude the yield term accordingly.
   *
   * @param nameGenerator the stateful mutable name generator object for
   *                      creating a new Metals generated name in each call.
   * @param perhapsLastName paramName from previous iteration
   *                        in `list.map(x => x + 1).flatMap(b => Some(b - 1))`,
   *                        if we are now processing `map`, it would be `b``
   * @param shouldFlat is it map or flatMap
   * @param existingForElements list of enumerators obtained from previous iterations
   * @param maybeCurrentYieldTerm the yield term from previous iterations if they
   *                              existed or `None``
   * @param nextQual in `list.map(x => x + 1)`, it is `x + 1``
   * @return (the list of deducted enumerators, maybe the deducted yield term)
   */
  private def obtainNextYieldAndElemsForMap(
      nameGenerator: MetalsNames,
      perhapsLastName: Option[String],
      shouldFlat: Boolean,
      existingForElements: List[Enumerator],
      maybeCurrentYieldTerm: Option[Term],
      nextQual: Term
  ): (List[Enumerator], Option[Term]) = {
    perhapsLastName match {
      case Some(lastName) =>
        (
          List(
            if (shouldFlat) { // when it is flatMap,
              // do lastName <- nextQual
              Enumerator.Generator(
                Pat.Var(Term.Name(lastName)),
                nextQual
              )
            } else
              Enumerator.Val( // when it is map
                // it is lastName = nextQual
                Pat.Var(Term.Name(lastName)),
                nextQual
              )
          ) ++ existingForElements,
          maybeCurrentYieldTerm // there was an iteration before this one,
          // so the yieldTerm comes from there
        )
      case None => // there was no iteration before this one
        // so it is list.map(x => x + 1)
        if (shouldFlat) { // when it is list.flatMap(x => Some(x + 1))
          // we have to generate a new parameter name, assign Some(x + 1)
          // to it, as in generatedByMetals0 <- Some(x + 1) to flatten the result
          val lastGeneratedName =
            nameGenerator.createNewName()
          val newEnumerations = List(
            Enumerator.Generator(
              Pat.Var(Term.Name(lastGeneratedName)),
              nextQual
            )
          )
          val newYield =
            Term.Name(lastGeneratedName) // then this new paramName,
          // generatedByMetals0, becomes the yield term.
          (newEnumerations, Some(newYield))
        } else
          (existingForElements, Some(nextQual)) // there is no flattening in
      // list.map(x => x + 1) so we just have `x + 1` as the ultimate yield term
    }
  }

  private def processMap(
      elems: List[Enumerator],
      maybeYieldTerm: Option[Term],
      nameGenerator: MetalsNames,
      valueName: String,
      termSelectQual: Term
  ): (List[Enumerator], Option[Term]) = {

    termSelectQual match { // prepare the next iteration
      case qualTermApply: Term.Apply => // the next termApply can potentially
        // be interesting
        extractChainedForYield(
          Some(valueName),
          maybeYieldTerm,
          elems,
          qualTermApply,
          nameGenerator
        )
      case otherQual => // there is no further termApply to process,
        // so we just assign what is left to the current valueName, as in
        // x <- list and prepend it to the list of other enumerators
        (
          Enumerator.Generator(
            Pat.Var(Term.Name(valueName)),
            otherQual
          )
            :: elems,
          maybeYieldTerm // return the already deducted yield term
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

    perhapsValueNameAndNextQual
      .map { case (valueName, nextCondition) =>
        val (elems, maybeYieldTerm): (List[Enumerator], Option[Term]) =
          perhapsLastName match {
            case Some(lastName) => // there was an iteration before it.
              (
                Enumerator.Val(
                  Pat.Var(
                    Term.Name(lastName)
                  ), // lastName gets paired with valueName
                  // so in List(1, 2, 3).filter( s => s > 1).map(x => x + 1)
                  // x is paired with s as in x = s
                  Term.Name(valueName)
                ) :: existingForElements,
                currentYieldTerm
              )
            case None =>
              (existingForElements, Some(Term.Name(valueName)))
            // there was no iteration before this one, so in
            // List(1, 2, 3).filter( s => s > 1) we would finally have
            // s as the ultimate yield
          }

        termSelectQual match { // move to preparing the next iteration
          case qualTermApply: Term.Apply => // we can potentially encounter
            // an interesting function, so we enter the next iteration to see
            extractChainedForYield(
              Some(valueName),
              maybeYieldTerm,
              Enumerator.Guard( // guard should come before x = s,
                // hence it should be prepended
                if (isFilter) nextCondition
                else
                  Term.ApplyUnary(Term.Name("!"), nextCondition)
              ) :: elems,
              qualTermApply,
              nameGenerator
            )
          case otherQual => // list
            ( // we are at the top end of the chain with no longer interesting
              // function to process as in list.filter(s => s > 1).map(x => x + 1)
              // so just assign otherQual to s, as in s <- list
              // and then add the guard after that, with
              // all the previous enumerators such as x = s after it.
              Enumerator.Generator(
                Pat.Var(Term.Name(valueName)),
                otherQual
              )
                :: Enumerator.Guard(
                  if (isFilter) nextCondition
                  else
                    Term.ApplyUnary(Term.Name("!"), nextCondition)
                ) :: elems,
              maybeYieldTerm
            )
        }
      }
      .getOrElse(
        List.empty,
        currentYieldTerm
      ) // when function passed to filter
    // cannot be processed to give us a parameter name and a condition, we just return an
    // empty list to avoid any further processing.
  }

  /**
   * This method traverses the chain of interesting applies from
   * the outermost to the innermost one, prepending the relevant
   * [[Enumerator]]s to the `existingForElements` in each iteration.
   *
   * the first thing it does is extracting a `paramName`, and `nextQual`
   * from the function passed as the argument of termApply.
   *
   * So for example, in
   * {{{
   * List(1, 2, 3)
   *    .map(x => x + 1)
   *    .filter(s => s > 7)
   * }}}
   * <p>if it had traversed `filter` in the previous iteration, the value of
   * `perhapseLastName` would be `s` which would be passed as argument when
   * we are passing `map` as the termApply. Also, `s` itself would be the value
   * of the so far extracted `maybeCurrentYieldTerm`, because of `filter`.
   *
   * <p>  then, entering in the current cycle, first `Some(x, x+1)` would get
   * extracted as `perhapsValueNameAndNextQual`.
   *
   * Now because in the current iteration we are on `map`/`flatMap`, `s` as the
   * param name of the last iteration is to be paired with `x + 1` as the
   * next qual, and prepended to the list of `existingForElements`, while `x` is to be
   * paired with either the whole qual value of the current [[Term.Select]], in this
   * case `List(1, 2, 3)`, or it is to be paired with the qual extracted from the
   * next iteration, in case, there is a `map`/`flatMap` before it.
   *
   * @param perhapsLastName the param name extracted from the termApply
   *                        in the last iteration
   * @param currentYieldTerm the so far extraxcted yield term from the previous iterations
   * @param existingForElements
   * @param termApply the termApply to be traveresed in this iteration
   * @param nameGenerator a stateful mutable object which is used for creating
   *                      non-overlapping
   *                      names for the anonymous parameters/placeholders of
   *                      the functions passed
   *                      as arguments to the interesting methods.
   * @return (the list of the so far extracted [[Enumerator]]s, maybe the
   *         so far extracted yield term)
   */
  private def extractChainedForYield(
      perhapsLastName: Option[String],
      currentYieldTerm: Option[Term],
      existingForElements: List[Enumerator],
      termApply: Term.Apply,
      nameGenerator: MetalsNames
  ): (List[Enumerator], Option[Term]) = {
    val perhapsValueNameAndNextQual = termApply.args.headOption.flatMap {
      processValueNameAndNextQual(
        _,
        nameGenerator
      )
    }

    termApply.fun match {
      case termSelect: Term.Select
          if termSelect.name.value == "flatMap" || termSelect.name.value == "map" =>
        val shouldFlat = termSelect.name.value == "flatMap"

        perhapsValueNameAndNextQual
          .map { case (valueName, nextQual) =>
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
          .getOrElse(List.empty, None)

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

      case _ => // there is no interesting function in this termApply
        perhapsLastName match {
          case Some(
                lastName
              ) => // if there were iterations before it, pair lastName
            // with this termApply
            // the yieldTerm is the one constructed in the previous iterations,
            // further down the chain
            (
              Enumerator.Generator(
                Pat.Var(Term.Name(lastName)),
                termApply
              )
                :: existingForElements,
              currentYieldTerm
            )
          case None => // if this is the first iteration,
            // just return the existingForElements and the termApply itself as yield
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
  val flatMapToForComprehension = "Convert to for comprehension"
}
