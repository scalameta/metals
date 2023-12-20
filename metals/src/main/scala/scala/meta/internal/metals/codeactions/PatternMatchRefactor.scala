package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.Term
import scala.meta.internal.metals.MetalsEnrichments.given
import scala.meta.internal.parsing.Trees
import scala.meta.io.AbsolutePath
import scala.meta.pc.CancelToken

import org.eclipse.{lsp4j => l}

class PatternMatchRefactor(trees: Trees) extends CodeAction {

  override val kind: String = l.CodeActionKind.RefactorRewrite

  private def convert(
      block: Term.Block,
      patternMatch: Term.Match,
      path: AbsolutePath,
  ): Seq[l.CodeAction] = patternMatch match {
    case Term.Match(Term.Placeholder(), head :: _) =>
      val lines = block.pos.input.text.split("\n")
      val patternMatchStartAtBlockLine =
        block.pos.startLine == patternMatch.pos.startLine
      val patternMatchEndAtBlockLine =
        block.pos.endLine == patternMatch.pos.endLine

      // determines indentation level of cases after TextEdit application
      val caseIndentSize =
        if (patternMatchStartAtBlockLine) head.pos.startColumn
        else patternMatch.pos.startColumn

      val previousIndentSize =
        lines(block.pos.startLine).takeWhile(_.isWhitespace).size
      val previousIndent = " " * previousIndentSize
      val baseIndentSize = caseIndentSize - previousIndentSize

      val range = patternMatch.pos.toLsp
      // if pattern match is in different line than map block overwrite indent
      if (!patternMatchStartAtBlockLine)
        range.getStart.setCharacter(0)

      val caseIndent = " " * caseIndentSize

      def indentCase(caze: meta.Case): String = {
        val toStrip = " " * (baseIndentSize)
        val lines = caze.toString.split("\n")
        val firstLine = lines.take(1).map(c => s"$caseIndent$c")
        val nestedLines =
          if (caze.pos.startColumn == caseIndentSize) lines.drop(1)
          else lines.drop(1).map(_.stripPrefix(toStrip))
        (firstLine ++ nestedLines).mkString("\n")
      }

      val cases = patternMatch.cases
        .map(indentCase)
        .mkString("\n")

      val text =
        (patternMatchStartAtBlockLine, patternMatchEndAtBlockLine) match {
          case (true, true) => s"\n$cases\n$previousIndent"
          case (true, false) => s"\n$cases"
          case (false, true) => s"$cases\n$previousIndent"
          case _ => cases
        }

      val edits = List(new l.TextEdit(range, text))

      val codeAction =
        CodeActionBuilder.build(
          title = PatternMatchRefactor.convertPatternMatch,
          kind = this.kind,
          changes = List(path -> edits),
        )

      Seq(codeAction)
    case _ => Seq.empty
  }

  override def contribute(params: l.CodeActionParams, token: CancelToken)(
      implicit ec: ExecutionContext
  ): Future[Seq[l.CodeAction]] = {

    val path = params.getTextDocument().getUri().toAbsolutePath
    val range = params.getRange()

    val isAnonymousFunctionWithMatch: Term.AnonymousFunction => Boolean = {
      case Term.AnonymousFunction(Term.Match(_)) => true
      case _ => false
    }

    val codeAction = trees
      .findLastEnclosingAt[Term.AnonymousFunction](
        path,
        range.getStart(),
        isAnonymousFunctionWithMatch,
      )
      .map {
        case func @ Term.AnonymousFunction(head: Term.Match) =>
          func.parent match {
            case Some(block: Term.Block) =>
              convert(block, head, path)
            case _ => Nil
          }
        case _ => Nil
      }
      .getOrElse(Nil)

    Future(codeAction)
  }

}

object PatternMatchRefactor {
  final val convertPatternMatch: String =
    "Convert pattern match with _ to partial function"
}
