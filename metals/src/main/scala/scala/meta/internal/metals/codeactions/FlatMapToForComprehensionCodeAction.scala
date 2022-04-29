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
          pprint.log("matched on Apply")
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
        case _ => None
      }
    }

    maybeCodeAction.flatten.toSeq

  }

  private def maybeBuildToForYieldCodeActionWithApply(
      path: AbsolutePath,
      termApply: Term.Apply,
      document: String,
      indentation: String
  ): Option[l.CodeAction] = {
    val (nameQualsList, yieldString) =
      extractForYield(List.empty, termApply, document)
    if (nameQualsList.nonEmpty) {
      val forYieldString =
        s"""|for {
            |${nameQualsList.map(nameQual => s"${indentation}  ${nameQual.variableName} <- ${nameQual.qual}").mkString("\n")}
            |${indentation}} yield {
            |${indentation}  ${yieldString}
            |${indentation}}""".stripMargin

      val codeAction = new l.CodeAction()
      val range =
        new l.Range(termApply.pos.toLSP.getStart, termApply.pos.toLSP.getEnd)
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
      Some(codeAction)
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

  case class ForYieldEnumeration(variableName: String, qual: String)
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
   * @param termApply the whole expression which is to have the name
   * @param document
   * @return a tuple of (List of (variableName, Qual), the candidate yield expression)
   */
  private def extractForYield(
      existingNameQuals: List[ForYieldEnumeration],
      termApply: Term.Apply,
      document: String
  ): (List[ForYieldEnumeration], YieldExpression) = {
    termApply.fun match {
      case termSelect: Term.Select
          if termSelect.name.value == "flatMap" || termSelect.name.value == "map" =>
        val qual = termSelect.qual
        val qualString = document.substring(qual.pos.start, qual.pos.end)
        termApply.args.head match {
          case termFunction: Term.Function =>
            extractForYieldOutOfFunction(
              existingNameQuals,
              termFunction,
              qualString,
              document
            )

          case termBlock: Term.Block =>
            termBlock.stats.collect { case termFunction: Term.Function =>
              extractForYieldOutOfFunction(
                existingNameQuals,
                termFunction,
                qualString,
                document
              )
            }.head

          case _ =>
            (
              existingNameQuals,
              YieldExpression(
                document.substring(termApply.pos.start, termApply.pos.end)
              )
            )
        }
      case _ =>
        (
          existingNameQuals,
          YieldExpression(
            document.substring(termApply.pos.start, termApply.pos.end)
          )
        )
    }
  }

  /**
   * @param existingNameQuals the `variableName <- qual` tuples which are extracted from the higher levels
   *                          of the hierarchy of nested `flatMaps`/`maps`, which would make up
   *                          the enumertions of the for yield expression, later
   * @param termFunction the function which is passed as the main argument of `.map` or `.flatMap`
   * @param qualString the left hand side of each enumeration item in the for yield expression
   * @param document the text string of the whole file
   * @return
   */
  private def extractForYieldOutOfFunction(
      existingNameQuals: List[ForYieldEnumeration],
      termFunction: Term.Function,
      qualString: String,
      document: String
  ): (List[ForYieldEnumeration], YieldExpression) = {
    val paramName =
      termFunction.params.headOption.map(_.name.value).getOrElse("")
    val body = termFunction.body
    body match {
      case bodyTermApply: Term.Apply =>
        extractForYield(
          existingNameQuals :+ ForYieldEnumeration(paramName, qualString),
          bodyTermApply,
          document
        )
      case otherBody =>
        (
          existingNameQuals :+ ForYieldEnumeration(paramName, qualString),
          YieldExpression(
            document.substring(otherBody.pos.start, otherBody.pos.end)
          )
        )
    }
  }

  private def getIndentationForPosition(
      treePos: Position
  ): String = {
    var result = ""
    for (i <- 0 to treePos.startColumn) {
      result = result + " "
    }
    result
  }

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
