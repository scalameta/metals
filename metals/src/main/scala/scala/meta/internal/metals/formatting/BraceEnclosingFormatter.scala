package scala.meta.internal.metals.formatting

import org.eclipse.lsp4j.{Range, TextEdit}

import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.{Template, Term, Tree}

case class BraceEnclosingFormatter(userConfig: () => UserConfiguration)
    extends OnTypeFormatter
    with RangeFormatter {

  private val openingBrace = '{'
  private val closingBrace = "}"

  override def contribute(
      params: OnTypeFormatterParams
  ): Option[List[TextEdit]] = {
    val triggerChar = params.triggerChar
    if (triggerChar == openingBrace) {
      val maybeApplyTree =
        params.trees
          .findLastEnclosingAt[Term.Apply](
            params.path,
            params.range.getStart,
            _ => true
          )

      maybeApplyTree.map { applyTree =>
        pprint.log(applyTree)
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

//  private def applyWithSingleFunction: Tree => Boolean = {
//    case _: Term.Apply | _:Template  => true
//    case _ => false
//  }

}
