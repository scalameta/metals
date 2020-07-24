package scala.meta.internal.metals

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.pc.CancelToken

import org.eclipse.{lsp4j => l}

trait CodeAction {

  /**
   * This should be one of the String constants
   * listed in [[org.eclipse.lsp4j.CodeActionKind]]
   */
  def kind: String

  def contribute(
      params: l.CodeActionParams,
      token: CancelToken
  )(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]]

  implicit val actionDiagnosticOrdering: Ordering[l.CodeAction] =
    new Ordering[l.CodeAction] {

      override def compare(
          x: l.CodeAction,
          y: l.CodeAction
      ): Int = {

        (
          x.getDiagnostics().asScala.headOption,
          y.getDiagnostics().asScala.headOption
        ) match {
          case (Some(diagx), Some(diagy)) =>
            val startx = diagx.getRange().getStart()
            val starty = diagy.getRange().getStart()
            val line = startx.getLine().compare(starty.getLine())
            if (line == 0) {
              startx.getCharacter().compare(starty.getCharacter())
            } else {
              line
            }
          case (Some(_), None) => 1
          case (None, Some(_)) => -1
          case _ => 0
        }
      }
    }
}
