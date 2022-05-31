package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
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

import scala.meta.internal.metals.codeactions.AssignOrMap.{AssignOrMap, assign}
import scala.meta.internal.metals.codeactions.FilterOrNot.FilterOrNot
import scala.util.Random

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
            applyWithSingleFunction
          )
      else
        None

    val maybeCodeAction = for {
      document <- buffers.get(path)
      applyTree <- maybeTree
      indentation = getIndentationForPosition(applyTree.pos)
    } yield {
      applyTree match {
        case termApply: Term.Apply =>
          maybeBuildToForYieldCodeActionWithApply(
            path,
            termApply,
            document,
            indentation
          )

        case termName: Term.Name
            if termName.value == "flatMap" || termName.value == "map" =>
          maybeBuildToForYieldCodeActionWithName(
            path,
            termName,
            document,
            indentation
          )
        //        case termIf: Term.If =>
        //          maybeBuildToForYieldCodeActionWithIf(
        //            path,
        //            termIf,
        //            document,
        //            indentation
        //          )
        case _ => None
      }
    }

    val maybeChainedCodeAction = for {
      document <- buffers.get(path)
      applyTree <- maybeTree
      indentation = getIndentationForPosition(applyTree.pos)
    } yield {
      applyTree match {
        case termApply: Term.Apply =>
          maybeBuildToForYieldChainedCodeActionWithApply(
            path,
            termApply,
            document,
            indentation
          )

        //        case termName: Term.Name
        //          if termName.value == "flatMap" || termName.value == "map" =>
        //          maybeBuildToForYieldChainedCodeActionWithName(
        //            path,
        //            termName,
        //            document,
        //            indentation
        //          )
        //        case termIf: Term.If =>
        //          maybeBuildToForYieldChainedCodeActionWithIf(
        //            path,
        //            termIf,
        //            document,
        //            indentation
        //          )
        case _ => None
      }
    }

    maybeChainedCodeAction.flatten.toSeq

  }

  //  private def maybeBuildToForYieldCodeActionWithIf(
  //                                                    path: AbsolutePath,
  //                                                    termIf: Term.If,
  //                                                    document: String,
  //                                                    indentation: String
  //                                                  ): Option[l.CodeAction] = {
  //    val (nameQualsList, conditions, yieldString) =
  //      extractForYieldOutOfIf(List.empty, List.empty, termIf, document)
  //    if (nameQualsList.nonEmpty)
  //      Some(
  //        buildCodeActionFromForYieldParts(
  //          nameQualsList,
  //          conditions,
  //          yieldString,
  //          indentation: String,
  //          path,
  //          termIf.pos.toLSP.getStart,
  //          termIf.pos.toLSP.getEnd
  //        )
  //      )
  //    else None
  //  }

  private def buildCodeActionFromForYieldParts(
      nameQualsList: List[ForYieldEnumeration],
      forYieldConditionsList: List[ForYieldCondition],
      yieldExpression: YieldExpression,
      indentation: String,
      path: AbsolutePath,
      startPos: l.Position,
      endPos: l.Position
  ): l.CodeAction = {

    val enumerations = nameQualsList.flatMap { nameQual =>
      for {
        valName <- nameQual.perhapsVariableName
        assignOrMap <- nameQual.perhapsAssignOrMap
        qual <- nameQual.qual
      } yield (valName, assignOrMap, qual)
    }

    val conditions = forYieldConditionsList.flatMap { condition =>
      for {
        conditionString <- condition.condition
        filterOrNot <- condition.maybeFilterOrNot
      } yield (filterOrNot, conditionString)

    }

    val forYieldString =
      s"""|for {
          |${enumerations.map(nameQual => s"${indentation}  ${nameQual._1} ${nameQual._2} ${nameQual._3}").mkString("\n")}
          |${conditions.map(forYieldCondition => s"${indentation}  if ${forYieldCondition._1}(${forYieldCondition._2})").mkString("\n")}
          |${indentation}} yield {
          |${indentation}  ${yieldExpression.expression}
          |${indentation}}""".stripMargin

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

  private def maybeBuildToForYieldCodeActionWithApply(
      path: AbsolutePath,
      termApply: Term.Apply,
      document: String,
      indentation: String
  ): Option[l.CodeAction] = {
    val (nameQualsList, conditions, yieldExpression) = {
      extractForYield(List.empty, List.empty, termApply, document)
    }

    if (nameQualsList.nonEmpty) {
      Some(
        buildCodeActionFromForYieldParts(
          nameQualsList,
          conditions,
          yieldExpression,
          indentation,
          path,
          termApply.pos.toLSP.getStart,
          termApply.pos.toLSP.getEnd
        )
      )
    } else None

  }

  private def maybeBuildToForYieldChainedCodeActionWithApply(
      path: AbsolutePath,
      termApply: Term.Apply,
      document: String,
      indentation: String
  ): Option[l.CodeAction] = {
    val (nameQualsList, conditions) = {
      extractChainedForYield(None, List.empty, List.empty, termApply, document)
    }

    if (nameQualsList.nonEmpty) {
      pprint.log("nameQualsList is: " + nameQualsList)
      nameQualsList match {
        case heads :+ tail =>
          tail.qual.map(yieldQual =>
            buildCodeActionFromForYieldParts(
              heads,
              conditions,
              YieldExpression(yieldQual),
              indentation,
              path,
              termApply.pos.toLSP.getStart,
              termApply.pos.toLSP.getEnd
            )
          )
        case head :: Nil => None
      }

    } else None

  }

  private def maybeBuildToForYieldCodeActionWithSelect(
      path: AbsolutePath,
      termSelect: Term.Select,
      document: String,
      indentation: String
  ): Option[l.CodeAction] = {
    termSelect.parent.collect { case termApply: Term.Apply =>
      maybeBuildToForYieldCodeActionWithApply(
        path,
        termApply,
        document,
        indentation
      )
    }.flatten
  }

  private def maybeBuildToForYieldCodeActionWithName(
      path: AbsolutePath,
      termName: Term.Name,
      document: String,
      indentation: String
  ): Option[l.CodeAction] = {
    termName.parent.collect { case termSelect: Term.Select =>
      maybeBuildToForYieldCodeActionWithSelect(
        path,
        termSelect,
        document,
        indentation
      )
    }.flatten

  }

  case class ForYieldEnumeration(
      perhapsAssignOrMap: Option[AssignOrMap],
      perhapsVariableName: Option[String],
      qual: Option[String]
  )

  case class ForYieldCondition(
      maybeFilterOrNot: Option[FilterOrNot],
      condition: Option[String]
  )

  case class ForYieldAssignment(variableName: String, assignment: String)

  case class YieldExpression(expression: String)

  /**
   * This recursive method, in each iteration, goes one level deeper in the hierarchy
   * of nested `maps`/`flatMap`s; and extracts the the `variableName <- qual` tuples,
   * then appends them to the `existingNameQuals` list.
   *
   * <p>The recursion stops, when there are no more `flatMap`s/`map`s further inward
   * in the hierarchy to extract `variableName <- qual` enumerations from. At such
   * a point, simply the body of the function passed as the argument of the innermost
   * `map`/`flatMap`, is regarded as the ultimate `yield` expression. For this reason,
   * the previously deducted yield expressions at the beginning of each iteration, get
   * ignored, so they don't get passed as an argument to the next recursive call.
   *
   * <p>Notice: this method does not preserve the 'flatness' level of the original
   * `flatMap`/`map` expression, in the produced for comprehension.
   *
   * @param existingNameQuals the `variableName <- qual` tuples which are extracted from the
   *                          higher levels of the hierarchy of nested `flatMaps`/`maps`,
   *                          which would make up the enumertions of the for yield expression, later
   * @param termApply         the whole expression which is to have the name
   * @param document
   * @return a tuple of (List of (variableName, Qual), the candidate yield expression)
   */
  private def extractForYield(
      existingNameQuals: List[ForYieldEnumeration],
      existingConditions: List[ForYieldCondition],
      termApply: Term.Apply,
      document: String
  ): (List[ForYieldEnumeration], List[ForYieldCondition], YieldExpression) = {
    termApply.fun match {
      case termSelect: Term.Select
          if termSelect.name.value == "flatMap" || termSelect.name.value == "map" =>
        val qual = termSelect.qual
        val qualString = document.substring(qual.pos.start, qual.pos.end)
        termApply.args.head match {
          case termFunction: Term.Function =>
            extractForYieldOutOfFunction(
              existingNameQuals,
              existingConditions,
              termFunction,
              qualString,
              document
            )

          case termBlock: Term.Block =>
            termBlock.stats
              .collectFirst { case termFunction: Term.Function =>
                extractForYieldOutOfFunction(
                  existingNameQuals,
                  existingConditions,
                  termFunction,
                  qualString,
                  document
                )
              }
              .getOrElse(
                (
                  existingNameQuals,
                  existingConditions,
                  YieldExpression(
                    document.substring(termApply.pos.start, termApply.pos.end)
                  )
                )
              )

          case _ =>
            (
              existingNameQuals,
              existingConditions,
              YieldExpression(
                document.substring(termApply.pos.start, termApply.pos.end)
              )
            )
        }
      case _ =>
        (
          existingNameQuals,
          existingConditions,
          YieldExpression(
            document.substring(termApply.pos.start, termApply.pos.end)
          )
        )
    }
  }

  private def extractNextValueNameAndNextQualOutOfTermApplyArgs(
      termApplyArgsHead: Term,
      document: String
  ): (
      Option[String],
      Option[String],
      List[ForYieldEnumeration],
      List[ForYieldCondition]
  ) = {
    termApplyArgsHead match {
      case termFunction: Term.Function =>
        val (valueName, nextQual, newEnums, newConditions) =
          extractValueNameAndNextQualOutOfFunction(
            termFunction,
            document
          )
        (Some(valueName), Some(nextQual), newEnums, newConditions)

      case termBlock: Term.Block =>
        termBlock.stats
          .collectFirst {
            case termFunction: Term.Function =>
              val (valueName, nextQual, newEnums, newConditions) =
                extractValueNameAndNextQualOutOfFunction(
                  termFunction,
                  document
                )
              (Some(valueName), Some(nextQual), newEnums, newConditions)
            //            case termApplyInfix: Term.ApplyInfix =>
            //              val (valueName, nextQual) = extractValueNameAndNextQualOutOfTermApplyInfix(
            //                termApplyInfix,
            //                document
            //              )
            //              (Some(valueName), Some(nextQual))
            case termName: Term.Name => // single argument function
              val (valueName, nextQual) =
                extractValueNameAndNextQualOutOfTermName(
                  termName,
                  document
                )
              (Some(valueName), Some(nextQual), List.empty, List.empty)
            //            case termApply: Term.Apply => //function and placeholder argument
            //              val (valueName, nextQual) = extractValueNameAndNextQualOutOfFunctionWithPlaceHolder(
            //                termApply,
            //                document
            //              )
            //              (Some(valueName), Some(nextQual))
          }
          .getOrElse((None, None, List.empty, List.empty))

      //      case termApplyInfix: Term.ApplyInfix =>
      //        val (valueName, nextQual) = extractValueNameAndNextQualOutOfTermApplyInfix(
      //          termApplyInfix,
      //          document
      //        )
      //        (Some(valueName), Some(nextQual))
      case termName: Term.Name =>
        val (valueName, nextQual) = extractValueNameAndNextQualOutOfTermName(
          termName,
          document
        )
        (Some(valueName), Some(nextQual), List.empty, List.empty)
      //      case termApply: Term.Apply =>
      //        val (valueName, nextQual) = extractValueNameAndNextQualOutOfFunctionWithPlaceHolder(
      //          termApply,
      //          document
      //        )
      //        (Some(valueName), Some(nextQual))
    }
  }

  private def extractChainedForYield(
      perhapseLastName: Option[String],
      existingNameQuals: List[ForYieldEnumeration],
      existingConditions: List[ForYieldCondition],
      termApply: Term.Apply,
      document: String
  ): (List[ForYieldEnumeration], List[ForYieldCondition]) = {
    termApply.fun match {
      case termSelect: Term.Select
          if termSelect.name.value == "flatMap" || termSelect.name.value == "map" =>
        val qual = termSelect.qual
        val assignOrMap =
          if (termSelect.name.value == "flatMap") AssignOrMap.map
          else AssignOrMap.assign
        val (perhapseValueName, perhapsNextQual, newEnums, newConditions) =
          extractNextValueNameAndNextQualOutOfTermApplyArgs(
            termApply.args.head,
            document
          )
        qual match {
          case qualTermApply: Term.Apply =>
            pprint.log("qualTermApply is: " + qualTermApply)
            extractChainedForYield(
              perhapseValueName,
              (newEnums :+ ForYieldEnumeration(
                Some(assignOrMap),
                perhapseLastName,
                perhapsNextQual
              )) ++ existingNameQuals,
              newConditions ++ existingConditions,
              qualTermApply,
              document
            )
          case otherQual =>
            pprint.log("otherQual is: " + otherQual)
            val qualString =
              document.substring(otherQual.pos.start, otherQual.pos.end)
            (
              (ForYieldEnumeration(
                Some(AssignOrMap.assign),
                perhapseValueName,
                Some(qualString)
              ) +: newEnums) ++ ((ForYieldEnumeration(
                Some(assignOrMap),
                perhapseLastName,
                perhapsNextQual
              ) +: existingNameQuals)),
              existingConditions
            )

        }

      case termSelect: Term.Select
          if termSelect.name.value == "filter" || termSelect.name.value == "filterNot" =>
        val qual = termSelect.qual
        val filterOrNot =
          if (termSelect.name.value == "filter") FilterOrNot.filter
          else FilterOrNot.filterNot
        val (perhapseValueName, perhapsNextCondition, newEnums, newConditions) =
          extractNextValueNameAndNextQualOutOfTermApplyArgs(
            termApply.args.head,
            document
          )
        qual match {
          case qualTermApply: Term.Apply =>
            pprint.log("qualTermApply is: " + qualTermApply)
            extractChainedForYield(
              perhapseValueName,
              (ForYieldEnumeration(
                Some(AssignOrMap.assign),
                perhapseLastName,
                perhapseValueName
              ) +: newEnums) ++ existingNameQuals,
              (ForYieldCondition(
                Some(filterOrNot),
                perhapsNextCondition
              ) +: newConditions) ++ existingConditions,
              qualTermApply,
              document
            )
          case otherQual =>
            pprint.log("otherQual is: " + otherQual)
            val qualString =
              document.substring(otherQual.pos.start, otherQual.pos.end)
            (
              (ForYieldEnumeration(
                Some(AssignOrMap.assign),
                perhapseValueName,
                Some(qualString)
              ) +: newEnums) ++ (ForYieldEnumeration(
                Some(AssignOrMap.assign),
                perhapseLastName,
                perhapseValueName
              ) +: existingNameQuals),
              ForYieldCondition(
                Some(filterOrNot),
                perhapsNextCondition
              ) +: existingConditions
            )

        }
      case otherFun =>
        pprint.log("otherFun type is " + otherFun.getClass.getSimpleName)
        val qualString =
          document.substring(termApply.pos.start, termApply.pos.end)
        (
          ForYieldEnumeration(
            Some(AssignOrMap.map),
            perhapseLastName,
            Some(qualString)
          ) +: existingNameQuals,
          existingConditions
        )
    }
  }

  def extractForYieldOutOfPotentaialYieldTree(
      existingNameQuals: List[ForYieldEnumeration],
      existingConditions: List[ForYieldCondition],
      yieldTree: Tree,
      document: String
  ): (List[ForYieldEnumeration], List[ForYieldCondition], YieldExpression) = {
    yieldTree match {
      case termApply: Term.Apply =>
        extractForYield(
          existingNameQuals,
          existingConditions,
          termApply,
          document
        )
      case termIf: Term.If =>
        extractForYieldOutOfIf(
          existingNameQuals,
          existingConditions,
          termIf,
          document
        )
      case termBlock: Term.Block if termBlock.stats.length == 1 =>
        extractForYieldOutOfPotentaialYieldTree(
          existingNameQuals,
          existingConditions,
          termBlock.stats.head,
          document
        )
      case otherStat =>
        (
          existingNameQuals,
          existingConditions,
          YieldExpression(
            document.substring(otherStat.pos.start, otherStat.pos.end)
          )
        )
    }
  }

  private def extractForYieldOutOfIf(
      existingNameQuals: List[ForYieldEnumeration],
      existingConditions: List[ForYieldCondition],
      termIf: Term.If,
      document: String
  ): (List[ForYieldEnumeration], List[ForYieldCondition], YieldExpression) = {
    termIf.elsep match {
      case termName: Term.Name if termName.value == "None" =>
        val condition =
          document.substring(termIf.cond.pos.start, termIf.cond.pos.end)
        val thenp = termIf.thenp
        extractForYieldOutOfPotentaialYieldTree(
          existingNameQuals,
          existingConditions :+ ForYieldCondition(None, Some(condition)),
          thenp,
          document
        )
      case _ =>
        (
          existingNameQuals,
          existingConditions,
          YieldExpression(
            document.substring(termIf.pos.start, termIf.pos.end)
          )
        )
    }
  }

  /**
   * @param existingNameQuals the `variableName <- qual` tuples which are extracted from the higher levels
   *                          of the hierarchy of nested `flatMaps`/`maps`, which would make up
   *                          the enumertions of the for yield expression, later
   * @param termFunction      the function which is passed as the main argument of `.map` or `.flatMap`
   * @param qualString        the left hand side of each enumeration item in the for yield expression
   * @param document          the text string of the whole file
   * @return
   */
  private def extractForYieldOutOfFunction(
      existingNameQuals: List[ForYieldEnumeration],
      existingConditions: List[ForYieldCondition],
      termFunction: Term.Function,
      qualString: String,
      document: String
  ): (List[ForYieldEnumeration], List[ForYieldCondition], YieldExpression) = {
    val paramName =
      termFunction.params.headOption.map(_.name.value).getOrElse("")
    val body = termFunction.body
    body match {
      case bodyTermApply: Term.Apply =>
        extractForYield(
          existingNameQuals :+ ForYieldEnumeration(
            Some(AssignOrMap.map),
            Some(paramName),
            Some(qualString)
          ),
          existingConditions,
          bodyTermApply,
          document
        )
//      case termIf: Term.If =>
//        extractForYieldOutOfIf(
//          existingNameQuals :+ ForYieldEnumeration(
//            None,
//            Some(paramName),
//            Some(qualString)
//          ),
//          existingConditions,
//          termIf,
//          document
//        )

      case otherBody =>
        (
          existingNameQuals :+ ForYieldEnumeration(
            Some(AssignOrMap.map),
            Some(paramName),
            Some(qualString)
          ),
          existingConditions,
          YieldExpression(
            s"Some( ${document.substring(otherBody.pos.start, otherBody.pos.end)})"
          )
        )
    }
  }

  private def extractValueNameAndNextQualOutOfFunction(
      termFunction: Term.Function,
      document: String
  ): (String, String, List[ForYieldEnumeration], List[ForYieldCondition]) = {
    val paramName =
      termFunction.params.headOption.map(_.name.value).getOrElse("")
    val body = termFunction.body
    body match {
      case termApplyBody: Term.Apply =>
        val (enumerations, conditions, yieldexpr) =
          extractForYield(List.empty, List.empty, termApplyBody, document)
        (paramName, yieldexpr.expression, enumerations, conditions)
      case otherBody =>
        (
          paramName,
          document.substring(otherBody.pos.start, otherBody.pos.end),
          List.empty,
          List.empty
        )
    }

  }

  private def extractValueNameAndNextQualOutOfTermName(
      termName: Term.Name,
      document: String
  ): (String, String) = {
    val paramName = "newValueByMetals" + Random.nextInt()
    (paramName, s"${termName.value}($paramName)")
  }

  private def getIndentationForPosition(
      treePos: Position
  ): String = {
    var result = ""
    for (_ <- 0 to treePos.startColumn) {
      result = result + " "
    }
    result
  }

  private def applyWithSingleFunction: Tree => Boolean = {
    case _: Term.Apply => true
    case _: Term.Select => true
    case _: Term.Name => true
    case _: Term.If => true
    case _ => false
  }
}

object FlatMapToForComprehensionCodeAction {
  val flatMapToForComprehension = "Turn into for comprehension"
}

object AssignOrMap extends Enumeration {
  type AssignOrMap = Value
  val assign = Value("=")
  val map = Value("<-")
}

object FilterOrNot extends Enumeration {
  type FilterOrNot = Value
  val filter = Value("")
  val filterNot = Value("!")
}
