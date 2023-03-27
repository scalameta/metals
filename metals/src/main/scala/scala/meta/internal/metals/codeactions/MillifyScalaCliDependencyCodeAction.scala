package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.codeactions.CodeAction
import scala.meta.internal.metals.codeactions.MillifyScalaCliDependencyCodeAction._
import scala.meta.internal.parsing.Trees
import scala.meta.io.AbsolutePath
import scala.meta.pc.CancelToken
import scala.meta.tokens.Token._

import org.eclipse.{lsp4j => l}

// TODO prepare LSP spec if it works in manual tests
// there are some similarities to MillifyDependencyCodeAction so maybe they can be extracted
class MillifyScalaCliDependencyCodeAction(buffers: Buffers) extends CodeAction {

  override def kind: String = l.CodeActionKind.QuickFix

  override def contribute(params: l.CodeActionParams, token: CancelToken)(
      implicit ec: ExecutionContext
  ): Future[Seq[l.CodeAction]] = Future {

    val path = params.getTextDocument().getUri().toAbsolutePath
    val range = params.getRange()
    val couldBeScalaCli = path.isScalaScript || path.isScala

    val tokenized =
      if (couldBeScalaCli && range.getStart == range.getEnd)
        buffers
          .get(path)
          .flatMap(Trees.defaultTokenizerDialect(_).tokenize.toOption)
      else None

    tokenized
      .flatMap { tokens =>
        tokens
          .filter(t =>
            t.pos.startLine == range.getStart.getLine
              && t.pos.endLine == range.getEnd.getLine
          )
          .collect {
            case comment: Comment
                if isScalaCliUsingDirectiveComment(comment.toString()) =>
              comment
          }
          .collectFirst { comment =>
            convertSbtToMillStyleIfPossible(comment.toString())
              .map(buildAction(comment, kind, path)(_))
              .map(List(_))
              .getOrElse(List.empty)
          }
      }
      .getOrElse(List.empty)
  }

}

object MillifyScalaCliDependencyCodeAction {

  private def buildAction(comment: Comment, kind: String, path: AbsolutePath)(
      replacementText: String
  ) =
    CodeActionBuilder.build(
      title = actionTitle(replacementText),
      kind = kind,
      changes =
        List(path -> List(new l.TextEdit(comment.pos.toLsp, replacementText))),
    )

  def convertSbtToMillStyleIfPossible(
      sbtStyleDirective: String
  ): Option[String] =
    sbtStyleDirective.split(" ") match {
      case Array(
            "//>",
            "using",
            dependencyIdentifierLike,
            groupId,
            groupDelimiter,
            artifactId,
            "%",
            version,
          )
          if dependencyIdentifiers(dependencyIdentifierLike) &&
            sbtDependencyDelimiters(groupDelimiter) =>
        val groupArtifactJoin = groupDelimiter.replace('%', ':')
        val millStyleDependency =
          s"$groupId$groupArtifactJoin$artifactId:$version".replace("\"", "")
        val replacementText =
          s"//> using $dependencyIdentifierLike \"$millStyleDependency\""
        Some(replacementText)
      case _ => None
    }

  private val dependencyIdentifiers = Set("dep", "lib", "plugin")
  private val sbtDependencyDelimiters = Set("%", "%%", "%%%")

  def isScalaCliUsingDirectiveComment(text: String) =
    text.startsWith("//> using")

  private def actionTitle(transformed: String): String =
    s"Convert to $transformed"

}
