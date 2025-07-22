package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.codeactions.CodeAction
import scala.meta.internal.parsing.Trees
import scala.meta.pc.CancelToken
import scala.meta.tokens.Token._

import org.eclipse.{lsp4j => l}

class MillifyDependencyCodeAction(buffers: Buffers) extends CodeAction {
  override def kind: String = l.CodeActionKind.RefactorRewrite

  override def contribute(params: l.CodeActionParams, token: CancelToken)(
      implicit ec: ExecutionContext
  ): Future[Seq[l.CodeAction]] = Future {
    val path = params.getTextDocument().getUri().toAbsolutePath
    val range = params.getRange()

    val tokenized =
      if ((path.isScalaScript || path.isMill) && range.getStart == range.getEnd)
        buffers
          .get(path)
          .flatMap(_.safeTokenize(Trees.defaultTokenizerDialect).toOption)
      else None

    tokenized
      .flatMap { tokens =>
        tokens
          .filter(t =>
            t.pos.startLine == range.getStart.getLine
              && t.pos.endLine == range.getEnd.getLine
          )
          .sliding(9)
          .collectFirst {
            case Seq(
                  start @ Constant.String(groupId),
                  (_: Space),
                  Ident(artifactExtra),
                  (_: Space),
                  Constant.String(artifactId),
                  (_: Space),
                  Ident("%"),
                  (_: Space),
                  end @ Constant.String(version),
                ) if Set("%", "%%", "%%%")(artifactExtra) =>
              val groupArtifactJoin = artifactExtra.replace('%', ':')
              val replacementText =
                s"$groupId$groupArtifactJoin$artifactId:$version"
              val pos = start.pos.toLsp
              pos.setEnd(end.pos.toLsp.getEnd)
              def action(text: String) = CodeActionBuilder.build(
                title = MillifyDependencyCodeAction.title(text),
                kind = this.kind,
                changes = List(path -> List(new l.TextEdit(pos, text))),
              )
              List(
                if (path.isMillBuild) action(s"mvn\"$replacementText\"")
                else if (path.isMill) action(s"ivy\"$replacementText\"")
                else action(s"`$replacementText`")
              )
          }
      }
      .getOrElse(List.empty)
  }
}

object MillifyDependencyCodeAction {
  def title(transformed: String): String = s"Convert to $transformed"
}
