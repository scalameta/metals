package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.Enumerator
import scala.meta.Name
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
          .findLastEnclosingAt[Tree](
            path,
            range.getStart(),
            isTreeInteresting
          )
      else
        None

    def parse(code: String): Option[Tree] = trees.parse(path)(code).toOption

    def parseTerm(code: String): Option[Term] =
      trees.parseTerm(path)(code).toOption

    val maybeChainedCodeAction = for {
      document <- buffers.get(path)
      applyTree <- maybeTree
      indentation = getIndentForPos(applyTree.pos, document)
    } yield {
      applyTree match {
        case termApply: Term.Apply =>
          codeActionWithApply(
            parseTerm,
            parse,
            path,
            termApply,
            document,
            indentation
          )
        case termSelect: Term.Select =>
          codeActionWithSelect(
            parseTerm,
            parse,
            path,
            termSelect,
            document,
            indentation
          )

        case termName: Term.Name =>
          codeActionWithName(
            parseTerm,
            parse,
            path,
            termName,
            document,
            indentation
          )

        case _ => None
      }
    }

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

  private def codeActionWithApply(
      parseTerm: String => Option[Term],
      parse: String => Option[Tree],
      path: AbsolutePath,
      termApply: Term.Apply,
      document: String,
      indentation: String
  ): Option[l.CodeAction] = {
    val (forElements, maybeYieldTerm, _) = {
      extractChainedForYield(
        parseTerm,
        parse,
        None,
        None,
        List.empty,
        termApply,
        document,
        Set.empty
      )
    }

    if (forElements.nonEmpty) {
      maybeYieldTerm.map { yieldTerm =>
        constructCodeAction(
          forElements,
          yieldTerm,
          indentation,
          path,
          termApply.pos.toLSP.getStart,
          termApply.pos.toLSP.getEnd
        )
      }
    } else None

  }

  private def codeActionWithSelect(
      parseTerm: String => Option[Term],
      parse: String => Option[Tree],
      path: AbsolutePath,
      termSelect: Term.Select,
      document: String,
      indentation: String
  ): Option[l.CodeAction] = {
    termSelect.parent.collect { case termApply: Term.Apply =>
      codeActionWithApply(
        parseTerm,
        parse,
        path,
        termApply,
        document,
        indentation
      )
    }.flatten
  }

  private def codeActionWithName(
      parseTerm: String => Option[Term],
      parse: String => Option[Tree],
      path: AbsolutePath,
      termName: Term.Name,
      document: String,
      indentation: String
  ): Option[l.CodeAction] = {
    termName.parent.collect { case termSelect: Term.Select =>
      codeActionWithSelect(
        parseTerm,
        parse,
        path,
        termSelect,
        document,
        indentation
      )
    }.flatten

  }

  private def replacePlaceHolder(tree: Term, newName: Term.Name): Term =
    tree match {
      case Term.Apply(fun, args) =>
        Term.Apply(
          replacePlaceHolder(fun, newName),
          args.map(replacePlaceHolder(_, newName))
        )
      case Term.Select(qual, name) =>
        Term.Select(replacePlaceHolder(qual, newName), name)
      case Term.Placeholder() => newName
      case other => other
    }

  private def processValueNameAndNextQual(
      tree: Tree,
      generatedByMetalsValues: Set[String]
  ): (Option[(String, Term)], Set[String]) = {
    tree match {
      case Term.Function(List(param), term) if param.name.value.isEmpty =>
        val (newName, generatedValues) =
          createNewName(tree, generatedByMetalsValues)
        (Some((newName, term)), generatedValues)
      case Term.Function(List(param), term) =>
        (Some((param.name.value, term)), generatedByMetalsValues)

      case Term.AnonymousFunction(term) =>
        val (newName, generatedValues) =
          createNewName(tree, generatedByMetalsValues)
        (
          Some((newName, replacePlaceHolder(term, Term.Name(newName)))),
          generatedValues
        )
      case Term.Block(List(function)) =>
        processValueNameAndNextQual(function, generatedByMetalsValues)
      case term: Term =>
        val (newName, generatedValues) =
          createNewName(tree, generatedByMetalsValues)
        (
          Some((newName, Term.Apply(term, List(Term.Name(newName))))),
          generatedValues
        )
    }
  }

  private def extractChainedForYield(
      parseTerm: String => Option[Term],
      parse: String => Option[Tree],
      perhapseLastName: Option[String],
      currentYieldTerm: Option[Term],
      existingForElements: List[Enumerator],
      termApply: Term.Apply,
      document: String,
      generatedByMetalsValues: Set[String]
  ): (List[Enumerator], Option[Term], Set[String]) = {
    val (perhapseValueNameAndNextQual, newMetalsNames) =
      processValueNameAndNextQual(
        termApply.args.head,
        generatedByMetalsValues
      )

    termApply.fun match {
      case termSelect: Term.Select
          if termSelect.name.value == "flatMap" || termSelect.name.value == "map" =>
        val qual = termSelect.qual
        val assignOrMap =
          if (termSelect.name.value == "flatMap") AssignOrMap.map
          else AssignOrMap.assign

        val result = for {
          valueName <- perhapseValueNameAndNextQual.map(_._1)
          nextQual <- perhapseValueNameAndNextQual.map(_._2)
        } yield {
          val (elems, maybeYieldTerm, updatedGenByMetalsVals) =
            perhapseLastName match {
              case Some(lastName) =>
                (
                  List(assignOrMap match {
                    case AssignOrMap.assign =>
                      Enumerator.Val.apply(
                        Pat.Var.apply(Term.Name.apply(lastName)),
                        nextQual
                      )
                    case AssignOrMap.map =>
                      Enumerator.Generator.apply(
                        Pat.Var.apply(Term.Name.apply(lastName)),
                        nextQual
                      )
                  }) ++ existingForElements,
                  currentYieldTerm,
                  newMetalsNames
                )
              case None =>
                assignOrMap match {
                  case AssignOrMap.assign =>
                    (existingForElements, Some(nextQual), newMetalsNames)

                  case AssignOrMap.map =>
                    val (lastGeneratedName, newGeneratedByMetalsVals) =
                      createNewName(termApply, newMetalsNames)
                    val newEnumerations = List(
                      Enumerator.Generator(
                        Pat.Var.apply(Term.Name.apply(lastGeneratedName)),
                        nextQual
                      )
                    )
                    val newYield = Term.Name.apply(lastGeneratedName)
                    (newEnumerations, Some(newYield), newGeneratedByMetalsVals)
                }
            }
          qual match {
            case qualTermApply: Term.Apply =>
              extractChainedForYield(
                parseTerm,
                parse,
                Some(valueName),
                maybeYieldTerm,
                elems,
                qualTermApply,
                document,
                updatedGenByMetalsVals
              )
            case otherQual =>
              (
                Enumerator.Generator(
                  Pat.Var.apply(Term.Name.apply(valueName)),
                  otherQual
                )
                  +: elems,
                maybeYieldTerm,
                updatedGenByMetalsVals
              )

          }
        }
        result.getOrElse(List.empty, None, newMetalsNames)

      case termSelect: Term.Select
          if termSelect.name.value == "filter" || termSelect.name.value == "filterNot" ||
            termSelect.name.value == "withFilter" =>
        val qual = termSelect.qual
        val filterOrNot =
          if (
            termSelect.name.value == "filter" || termSelect.name.value == "withFilter"
          ) FilterOrNot.filter
          else FilterOrNot.filterNot
        val result = for {
          valueName <- perhapseValueNameAndNextQual.map(_._1)
          nextCondition <- perhapseValueNameAndNextQual.map(_._2)
        } yield {
          val (elems, maybeYieldTerm): (List[Enumerator], Option[Term]) =
            perhapseLastName match {
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

          qual match {
            case qualTermApply: Term.Apply =>
              extractChainedForYield(
                parseTerm,
                parse,
                Some(valueName),
                maybeYieldTerm,
                Enumerator.Guard.apply(
                  filterOrNot match {
                    case FilterOrNot.filter => nextCondition
                    case FilterOrNot.filterNot =>
                      Term.ApplyUnary.apply(Term.Name.apply("!"), nextCondition)
                  }
                ) +: elems,
                qualTermApply,
                document,
                newMetalsNames
              )
            case otherQual =>
              (
                Enumerator.Generator(
                  Pat.Var.apply(Term.Name.apply(valueName)),
                  otherQual
                )
                  +: Enumerator.Guard.apply(
                    filterOrNot match {
                      case FilterOrNot.filter => nextCondition
                      case FilterOrNot.filterNot =>
                        Term.ApplyUnary
                          .apply(Term.Name.apply("!"), nextCondition)
                    }
                  ) +: elems,
                maybeYieldTerm,
                newMetalsNames
              )
          }
        }
        result.getOrElse(List.empty, currentYieldTerm, generatedByMetalsValues)
      case _ =>
        perhapseLastName match {
          case Some(lastName) =>
            (
              Enumerator.Generator(
                Pat.Var.apply(Term.Name.apply(lastName)),
                termApply
              )
                +: existingForElements,
              currentYieldTerm,
              generatedByMetalsValues
            )
          case None =>
            (existingForElements, Some(termApply), generatedByMetalsValues)
        }
    }
  }

  private def createNewName(
      tree: Tree,
      generatedByMetalsValues: Set[String]
  ): (String, Set[String]) = {

    def findTopMostParent: Tree = {
      var initialParent = tree
      while (initialParent.parent.isDefined)
        initialParent = initialParent.parent.get

      initialParent
    }

    // We don't want to use any name that is already being used in the scope
    def loop(t: Tree): List[String] = {
      t.children.flatMap {
        case n: Name => List(n.toString())
        case child => loop(child)
      }
    }

    val newValuePrefix = "generatedByMetals"
    val names = loop(findTopMostParent).toSet ++ generatedByMetalsValues

    if (!names(newValuePrefix))
      (newValuePrefix, generatedByMetalsValues + newValuePrefix)
    else {
      var i = 0
      while (names(s"$newValuePrefix$i"))
        i += 1
      val result = s"$newValuePrefix$i"
      (result, generatedByMetalsValues + result)
    }
  }

  private def getIndentForPos(
      treePos: Position,
      document: String
  ): String =
    document
      .substring(treePos.start - treePos.startColumn, treePos.start)
      .takeWhile(_.isWhitespace)

  private def isTreeInteresting: Tree => Boolean = {
    case _: Term.Apply => true
    case termSelect: Term.Select
        if termSelect.name.value == "map" || termSelect.name.value == "flatMap" ||
          termSelect.name.value == "filter" || termSelect.name.value == "filterNot" ||
          termSelect.name.value == "withFilter" =>
      true
    case termName: Term.Name
        if termName.value == "flatMap" || termName.value == "map" ||
          termName.value == "filter" || termName.value == "filterNot" ||
          termName.value == "withFilter" =>
      true
    case _ => false
  }
}

object FlatMapToForComprehensionCodeAction {
  val flatMapToForComprehension = "Turn into for comprehension"
}

object AssignOrMap extends Enumeration {
  type AssignOrMap = Value
  val assign: Value = Value("=")
  val map: Value = Value("<-")
}

object FilterOrNot extends Enumeration {
  type FilterOrNot = Value
  val filter: Value = Value("")
  val filterNot: Value = Value("!")
}
