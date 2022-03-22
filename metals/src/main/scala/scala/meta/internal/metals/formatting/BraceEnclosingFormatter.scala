package scala.meta.internal.metals.formatting

import org.eclipse.lsp4j.{Range, TextEdit}

import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.{Term, Tree}

case class BraceEnclosingFormatter(userConfig: () => UserConfiguration)
    extends OnTypeFormatter
    with RangeFormatter {

  private val openingBrace = '{'
  private val closingBrace = "}"

  override def contribute(
      params: OnTypeFormatterParams
  ): Option[List[TextEdit]] = {
    val position = params.position
    val triggerChar = params.triggerChar
    if (triggerChar == openingBrace) {
      val maybeApplyTree =
        if (params.range.getStart == params.range.getEnd)
          params.trees
            .findLastEnclosingAt[Tree](
              params.path,
              position,
              applyWithSingleFunction
            )
        else None
      maybeApplyTree.map { applyTree =>
        val textEdit = new TextEdit()
        textEdit.setNewText(closingBrace)
        applyTree.pos.toLSP.getEnd
        textEdit.setRange(
          new Range(applyTree.pos.toLSP.getEnd, applyTree.pos.toLSP.getEnd)
        )
        List(textEdit)
      }

    } else None
  }

  private def applyWithSingleFunction: Tree => Boolean = {
    case _: Term.Apply => true
    case _ => false
  }

}
