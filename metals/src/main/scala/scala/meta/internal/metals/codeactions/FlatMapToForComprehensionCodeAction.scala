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
            isTreeInteresting
          )
      else
        None

    def parse(code: String): Option[Tree] = trees.parse(path)(code).toOption
    val maybeChainedCodeAction = for {
      document <- buffers.get(path)
      applyTree <- maybeTree
      indentation = getIndentForPos(applyTree.pos, document)
    } yield {
      applyTree match {
        case termApply: Term.Apply =>
          codeActionWithApply(
            parse,
            path,
            termApply,
            document,
            indentation
          )
        case termSelect: Term.Select =>
          codeActionWithSelect(
            parse,
            path,
            termSelect,
            document,
            indentation
          )

        case termName: Term.Name =>
          codeActionWithName(
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
      forElementsList: List[ForElement],
      yieldExpression: YieldExpression,
      indentation: String,
      path: AbsolutePath,
      startPos: l.Position,
      endPos: l.Position
  ): l.CodeAction = {

    val elements = forElementsList.flatMap {
      case ForYieldEnumeration(perhapsAssignOrMap, perhapsVariableName, qual) =>
        for {
          valName <- perhapsVariableName
          assignOrMap <- perhapsAssignOrMap
          qualTree <- qual
          qualString = qualTree.toString
          qualLines = qualString.split(Array('\n'))
          finalQual = (qualLines.head +: qualLines.tail.map(line =>
            s"${indentation}${indentation}      $line"
          )).mkString("\n")
        } yield s"${indentation}  ${valName} ${assignOrMap} ${finalQual}"

      case ForYieldCondition(maybeFilterOrNot, condition) =>
        for {
          filterOrNot <- maybeFilterOrNot
          conditionString <- condition
        } yield s"${indentation}  if $filterOrNot($conditionString)"
    }

    val forYieldString =
      s"""|for {
          |${elements.mkString("\n")}
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

  private def codeActionWithApply(
      parse: String => Option[Tree],
      path: AbsolutePath,
      termApply: Term.Apply,
      document: String,
      indentation: String
  ): Option[l.CodeAction] = {
    val (forElements, _) = {
      extractChainedForYield(
        parse,
        None,
        List.empty,
        termApply,
        document,
        Set.empty
      )
    }

    if (forElements.nonEmpty) {
      forElements match {
        case heads :+ tail =>
          tail
            .asInstanceOf[ForYieldEnumeration]
            .qual
            .map(yieldQual =>
              constructCodeAction(
                heads,
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

  private def codeActionWithSelect(
      parse: String => Option[Tree],
      path: AbsolutePath,
      termSelect: Term.Select,
      document: String,
      indentation: String
  ): Option[l.CodeAction] = {
    termSelect.parent.collect { case termApply: Term.Apply =>
      codeActionWithApply(
        parse,
        path,
        termApply,
        document,
        indentation
      )
    }.flatten
  }

  private def codeActionWithName(
      parse: String => Option[Tree],
      path: AbsolutePath,
      termName: Term.Name,
      document: String,
      indentation: String
  ): Option[l.CodeAction] = {
    termName.parent.collect { case termSelect: Term.Select =>
      codeActionWithSelect(
        parse,
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
      qual: Option[EnumerationValue]
  ) extends ForElement

  trait EnumerationValue
  case class TreeEnumerationValue(qual: Tree) extends EnumerationValue {
    override def toString: String = qual.syntax
  }

  case class StringEnumerationValue(qual: String) extends EnumerationValue {
    override def toString: String = qual
  }

  case class ForYieldCondition(
      maybeFilterOrNot: Option[FilterOrNot],
      condition: Option[EnumerationValue]
  ) extends ForElement

  trait ForElement

  case class YieldExpression(expression: EnumerationValue)

  private def processValueNameAndNextQual(
      parse: String => Option[Tree],
      tree: Tree,
      document: String,
      generatedByMetalsValues: Set[String]
  ): (Option[String], Option[EnumerationValue], Set[String]) = tree match {
    case termFunction: Term.Function =>
      processFunction(
        termFunction,
        generatedByMetalsValues
      )
    case termApplyInfix: Term.ApplyInfix =>
      processApplyInfix(
        parse,
        termApplyInfix,
        document,
        generatedByMetalsValues
      )
    case termName: Term.Name => // single argument function
      val (valueName, nextQual, newMetalsNames) =
        processTermName(
          termName,
          generatedByMetalsValues
        )
      (valueName, nextQual, newMetalsNames)
    case termApply: Term.Apply => // function and placeholder argument
      processTermApply(
        parse,
        termApply,
        document,
        generatedByMetalsValues
      )
    case termBlock: Term.Block =>
      termBlock.stats.headOption
        .map(
          processValueNameAndNextQual(
            parse,
            _,
            document,
            generatedByMetalsValues
          )
        )
        .getOrElse(None, None, generatedByMetalsValues)

    case termAnonymousFunction: Term.AnonymousFunction =>
      termAnonymousFunction.body match {
        case termApplyInfix: Term.ApplyInfix =>
          processApplyInfix(
            parse,
            termApplyInfix,
            document,
            generatedByMetalsValues
          )
        case termName: Term.Name => // single argument function
          val (valueName, nextQual, newMetalsNames) =
            processTermName(
              termName,
              generatedByMetalsValues
            )
          (valueName, nextQual, newMetalsNames)
        case termApply: Term.Apply => // function and placeholder argument
          processTermApply(
            parse,
            termApply,
            document,
            generatedByMetalsValues
          )
        case _ => (None, None, generatedByMetalsValues)
      }
  }

  private def processTermApply(
      parse: String => Option[Tree],
      termApply: Term.Apply,
      document: String,
      generatedByMetalsValues: Set[String]
  ): (Option[String], Option[EnumerationValue], Set[String]) = {
    val (nextValueName, newMetalsValues) =
      createNewName(termApply, generatedByMetalsValues)
    val maybeNextQual =
      replacePlaceHolderWithNewValue(nextValueName, termApply, document)
    val maybeNextQualTree =
      maybeNextQual.flatMap(parse).map(TreeEnumerationValue)
    (maybeNextQual.map(_ => nextValueName), maybeNextQualTree, newMetalsValues)
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

  private def processApplyInfix(
      parse: String => Option[Tree],
      termApplyInfix: Term.ApplyInfix,
      document: String,
      generatedByMetalsValues: Set[String]
  ): (Option[String], Option[EnumerationValue], Set[String]) = {
    ???
  }

  private def extractChainedForYield(
      parse: String => Option[Tree],
      perhapseLastName: Option[String],
      existingForElements: List[ForElement],
      termApply: Term.Apply,
      document: String,
      generatedByMetalsValues: Set[String]
  ): (List[ForElement], Set[String]) = {
    termApply.fun match {
      case termSelect: Term.Select
          if termSelect.name.value == "flatMap" || termSelect.name.value == "map" =>
        val qual = termSelect.qual
        val assignOrMap =
          if (termSelect.name.value == "flatMap") AssignOrMap.map
          else AssignOrMap.assign
        val (perhapseValueName, perhapsNextQual, newMetalsNames) =
          processValueNameAndNextQual(
            parse,
            termApply.args.head,
            document,
            generatedByMetalsValues
          )
        qual match {
          case qualTermApply: Term.Apply =>
            extractChainedForYield(
              parse,
              perhapseValueName,
              ForYieldEnumeration(
                Some(assignOrMap),
                perhapseLastName,
                perhapsNextQual
              ) +: existingForElements,
              qualTermApply,
              document,
              newMetalsNames
            )
          case otherQual =>
            (
              ForYieldEnumeration(
                Some(AssignOrMap.assign),
                perhapseValueName,
                Some(TreeEnumerationValue(otherQual))
              ) +: ForYieldEnumeration(
                Some(assignOrMap),
                perhapseLastName,
                perhapsNextQual
              ) +: existingForElements,
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
          processValueNameAndNextQual(
            parse,
            termApply.args.head,
            document,
            generatedByMetalsValues
          )
        qual match {
          case qualTermApply: Term.Apply =>
            extractChainedForYield(
              parse,
              perhapseValueName,
              ForYieldCondition(
                Some(filterOrNot),
                perhapsNextCondition
              ) +: ForYieldEnumeration(
                Some(AssignOrMap.assign),
                perhapseLastName,
                perhapseValueName.map(StringEnumerationValue)
              ) +: existingForElements,
              qualTermApply,
              document,
              newMetalsNames
            )
          case otherQual =>
            (
              ForYieldEnumeration(
                Some(AssignOrMap.assign),
                perhapseValueName,
                Some(TreeEnumerationValue(otherQual))
              ) +: ForYieldCondition(
                Some(filterOrNot),
                perhapsNextCondition
              ) +: ForYieldEnumeration(
                Some(AssignOrMap.assign),
                perhapseLastName,
                perhapseValueName.map(StringEnumerationValue)
              ) +: existingForElements,
              newMetalsNames
            )

        }
      case _ =>
        (
          ForYieldEnumeration(
            Some(AssignOrMap.map),
            perhapseLastName,
            Some(TreeEnumerationValue(termApply))
          ) +: existingForElements,
          generatedByMetalsValues
        )
    }
  }

  private def processFunction(
      termFunction: Term.Function,
      generatedByMetalsValues: Set[String]
  ): (Option[String], Option[EnumerationValue], Set[String]) = {
    val maybeNameAndMetalsNames = termFunction.params.headOption
      .map {
        case anonymousParam if anonymousParam.name.value == "" =>
          createNewName(termFunction, generatedByMetalsValues)
        case otherParam => (otherParam.name.value, generatedByMetalsValues)
      }
    (
      maybeNameAndMetalsNames.map(_._1),
      maybeNameAndMetalsNames.map(_ => TreeEnumerationValue(termFunction.body)),
      maybeNameAndMetalsNames.map(_._2).getOrElse(generatedByMetalsValues)
    )
  }

  private def processTermName(
      termName: Term.Name,
      generatedByMetalsValues: Set[String]
  ): (Option[String], Option[EnumerationValue], Set[String]) = {
    val (paramName, newMetalsNames) =
      createNewName(termName, generatedByMetalsValues)
    val nextQual = StringEnumerationValue(s"${termName.value}($paramName)")
    (Some(paramName), Some(nextQual), newMetalsNames)
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
          termSelect.name.value == "filter" || termSelect.name.value == "filterNot" =>
      true
    case termName: Term.Name
        if termName.value == "flatMap" || termName.value == "map" ||
          termName.value == "filter" || termName.value == "filterNot" =>
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
