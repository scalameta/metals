package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.Name
import scala.meta.Term
import scala.meta.Tree
import scala.meta.inputs.Position
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.CodeAction
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.codeactions.AssignOrMap.AssignOrMap
import scala.meta.internal.metals.codeactions.FilterOrNot.FilterOrNot
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
            applyWithSingleFunction
          )
      else
        None

    val maybeChainedCodeAction = for {
      document <- buffers.get(path)
      applyTree <- maybeTree
      indentation = getIndentationForPositionInDocument(applyTree.pos, document)
    } yield {
      applyTree match {
        case termApply: Term.Apply =>
          maybeBuildToForYieldChainedCodeActionWithApply(
            path,
            termApply,
            document,
            indentation
          )
        case termSelect: Term.Select
            if termSelect.name.value == "map" || termSelect.name.value == "flatMap" ||
              termSelect.name.value == "filter" || termSelect.name.value == "filterNot" =>
          maybeBuildToForYieldCodeActionWithSelect(
            path,
            termSelect,
            document,
            indentation
          )

        case termName: Term.Name
            if termName.value == "flatMap" || termName.value == "map" ||
              termName.value == "filter" || termName.value == "filterNot" =>
          maybeBuildToForYieldCodeActionWithName(
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

  private def maybeBuildToForYieldChainedCodeActionWithApply(
      path: AbsolutePath,
      termApply: Term.Apply,
      document: String,
      indentation: String
  ): Option[l.CodeAction] = {
    val (nameQualsList, conditions, _) = {
      extractChainedForYield(
        None,
        List.empty,
        List.empty,
        termApply,
        document,
        Set.empty
      )
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
        case _ :: Nil => None
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
      maybeBuildToForYieldChainedCodeActionWithApply(
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

  private def extractNextValueNameAndNextQualOutOfTermApplyArgs(
      termApplyArgsHead: Term,
      document: String,
      generatedByMetalsValues: Set[String]
  ): (
      Option[String],
      Option[String],
      Set[String]
  ) = {
    processValueNameAndNextQual(
      termApplyArgsHead,
      document,
      generatedByMetalsValues
    )
  }

  private def processValueNameAndNextQual(
      tree: Tree,
      document: String,
      generatedByMetalsValues: Set[String]
  ): (Option[String], Option[String], Set[String]) = tree match {
    case termFunction: Term.Function =>
      extractValueNameAndNextQualOutOfFunction(
        termFunction,
        document,
        generatedByMetalsValues
      )
    case termApplyInfix: Term.ApplyInfix =>
      extractValueNameAndNextQualOutOfApplyInfix(
        termApplyInfix,
        document,
        generatedByMetalsValues
      )
    case termName: Term.Name => // single argument function
      val (valueName, nextQual, newMetalsNames) =
        extractValueNameAndNextQualOutOfTermName(
          termName,
          generatedByMetalsValues
        )
      (Some(valueName), Some(nextQual), newMetalsNames)
    case termApply: Term.Apply => // function and placeholder argument
      extractValueNameAndNextQualOutOfTermApply(
        termApply,
        document,
        generatedByMetalsValues
      )
    case termBlock: Term.Block =>
      termBlock.stats.headOption
        .map(processValueNameAndNextQual(_, document, generatedByMetalsValues))
        .getOrElse(None, None, generatedByMetalsValues)

    case termAnonymousFunction: Term.AnonymousFunction =>
      termAnonymousFunction.body match {
        case termApplyInfix: Term.ApplyInfix =>
          extractValueNameAndNextQualOutOfApplyInfix(
            termApplyInfix,
            document,
            generatedByMetalsValues
          )
        case termName: Term.Name => // single argument function
          val (valueName, nextQual, newMetalsNames) =
            extractValueNameAndNextQualOutOfTermName(
              termName,
              generatedByMetalsValues
            )
          (Some(valueName), Some(nextQual), newMetalsNames)
        case termApply: Term.Apply => // function and placeholder argument
          extractValueNameAndNextQualOutOfTermApply(
            termApply,
            document,
            generatedByMetalsValues
          )
        case _ => (None, None, generatedByMetalsValues)
      }
  }

  private def extractValueNameAndNextQualOutOfTermApply(
      termApply: Term.Apply,
      document: String,
      generatedByMetalsValues: Set[String]
  ): (Option[String], Option[String], Set[String]) = {
    val (nextValueName, newMetalsValues) =
      createNewName(termApply, generatedByMetalsValues)
    val maybeNextQual =
      replacePlaceHolderWithNewValue(nextValueName, termApply, document)
    (maybeNextQual.map(_ => nextValueName), maybeNextQual, newMetalsValues)
  }

  private def replacePlaceHolderWithNewValue(
      newValueName: String,
      termApply: Term.Apply,
      document: String
  ): Option[String] = {
    val maybePlaceHolderFromSelectQual = termApply.fun match {
      case termSelect: Term.Select =>
        termSelect.qual match {
          case placeHolder: Term.Placeholder => Some(placeHolder)
          case _ => None
        }
      case _ => None
    }
    val maybePlaceHolder = maybePlaceHolderFromSelectQual.orElse {
      termApply.args.collectFirst { case placeHolder: Term.Placeholder =>
        placeHolder
      }
    }
    maybePlaceHolder.map { placeHolder =>
      val newTermApply =
        document.substring(termApply.pos.start, termApply.pos.end)
      newTermApply.patch(
        placeHolder.pos.start - termApply.pos.start,
        newValueName,
        1
      )
    }
  }

  private def extractValueNameAndNextQualOutOfApplyInfix(
      termApplyInfix: Term.ApplyInfix,
      document: String,
      generatedByMetalsValues: Set[String]
  ): (Option[String], Option[String], Set[String]) = {
    ???
  }

  private def extractChainedForYield(
      perhapseLastName: Option[String],
      existingNameQuals: List[ForYieldEnumeration],
      existingConditions: List[ForYieldCondition],
      termApply: Term.Apply,
      document: String,
      generatedByMetalsValues: Set[String]
  ): (List[ForYieldEnumeration], List[ForYieldCondition], Set[String]) = {
    termApply.fun match {
      case termSelect: Term.Select
          if termSelect.name.value == "flatMap" || termSelect.name.value == "map" =>
        val qual = termSelect.qual
        val assignOrMap =
          if (termSelect.name.value == "flatMap") AssignOrMap.map
          else AssignOrMap.assign
        val (perhapseValueName, perhapsNextQual, newMetalsNames) =
          extractNextValueNameAndNextQualOutOfTermApplyArgs(
            termApply.args.head,
            document,
            generatedByMetalsValues
          )
        qual match {
          case qualTermApply: Term.Apply =>
            pprint.log("qualTermApply is: " + qualTermApply)
            extractChainedForYield(
              perhapseValueName,
              ForYieldEnumeration(
                Some(assignOrMap),
                perhapseLastName,
                perhapsNextQual
              ) +: existingNameQuals,
              existingConditions,
              qualTermApply,
              document,
              newMetalsNames
            )
          case otherQual =>
            pprint.log("otherQual is: " + otherQual)
            val qualString =
              document.substring(otherQual.pos.start, otherQual.pos.end)
            (
              ForYieldEnumeration(
                Some(AssignOrMap.assign),
                perhapseValueName,
                Some(qualString)
              ) +: ForYieldEnumeration(
                Some(assignOrMap),
                perhapseLastName,
                perhapsNextQual
              ) +: existingNameQuals,
              existingConditions,
              newMetalsNames
            )

        }

      case termSelect: Term.Select
          if termSelect.name.value == "filter" || termSelect.name.value == "filterNot" =>
        val qual = termSelect.qual
        val filterOrNot =
          if (termSelect.name.value == "filter") FilterOrNot.filter
          else FilterOrNot.filterNot
        val (perhapseValueName, perhapsNextCondition, newMetalsNames) =
          extractNextValueNameAndNextQualOutOfTermApplyArgs(
            termApply.args.head,
            document,
            generatedByMetalsValues
          )
        qual match {
          case qualTermApply: Term.Apply =>
            pprint.log("qualTermApply is: " + qualTermApply)
            extractChainedForYield(
              perhapseValueName,
              ForYieldEnumeration(
                Some(AssignOrMap.assign),
                perhapseLastName,
                perhapseValueName
              ) +: existingNameQuals,
              ForYieldCondition(
                Some(filterOrNot),
                perhapsNextCondition
              ) +: existingConditions,
              qualTermApply,
              document,
              newMetalsNames
            )
          case otherQual =>
            pprint.log("otherQual is: " + otherQual)
            val qualString =
              document.substring(otherQual.pos.start, otherQual.pos.end)
            (
              ForYieldEnumeration(
                Some(AssignOrMap.assign),
                perhapseValueName,
                Some(qualString)
              ) +: ForYieldEnumeration(
                Some(AssignOrMap.assign),
                perhapseLastName,
                perhapseValueName
              ) +: existingNameQuals,
              ForYieldCondition(
                Some(filterOrNot),
                perhapsNextCondition
              ) +: existingConditions,
              newMetalsNames
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
          existingConditions,
          generatedByMetalsValues
        )
    }
  }

  private def extractValueNameAndNextQualOutOfFunction(
      termFunction: Term.Function,
      document: String,
      generatedByMetalsValues: Set[String]
  ): (Option[String], Option[String], Set[String]) = {
    val maybeNameAndMetalsNames = termFunction.params.headOption
      .map {
        case anonymousParam if anonymousParam.name.value == "" =>
          createNewName(termFunction, generatedByMetalsValues)
        case otherParam => (otherParam.name.value, generatedByMetalsValues)
      }
    val newQual =
      document.substring(termFunction.body.pos.start, termFunction.body.pos.end)
    (
      maybeNameAndMetalsNames.map(_._1),
      maybeNameAndMetalsNames.map(_ => newQual),
      maybeNameAndMetalsNames.map(_._2).getOrElse(generatedByMetalsValues)
    )
  }

  private def extractValueNameAndNextQualOutOfTermName(
      termName: Term.Name,
      generatedByMetalsValues: Set[String]
  ): (String, String, Set[String]) = {
    val (paramName, newMetalsNames) =
      createNewName(termName, generatedByMetalsValues)
    (paramName, s"${termName.value}($paramName)", newMetalsNames)
  }

  private def createNewName(
      tree: Tree,
      generatedByMetalsValues: Set[String]
  ): (String, Set[String]) = {

    def findTopMostParent = {
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

  private def getIndentationForPositionInDocument(
      treePos: Position,
      document: String
  ): String =
    document
      .substring(treePos.start - treePos.startColumn, treePos.start)
      .takeWhile(_.isWhitespace)

  private def applyWithSingleFunction: Tree => Boolean = {
    case _: Term.Apply => true
    case _: Term.Select => true
    case _: Term.Name => true
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
