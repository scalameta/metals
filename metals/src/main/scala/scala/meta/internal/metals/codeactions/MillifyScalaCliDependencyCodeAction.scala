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

class MillifyScalaCliDependencyCodeAction(buffers: Buffers) extends CodeAction {

  override def kind: String = l.CodeActionKind.QuickFix

  override def contribute(
      params: l.CodeActionParams,
      token: CancelToken,
  )(implicit
      ec: ExecutionContext
  ): Future[Seq[l.CodeAction]] = Future {

    val path = params.getTextDocument().getUri().toAbsolutePath
    val range = params.getRange()
    val couldBeScalaCli = path.isScalaScript || path.isScala

    val tokenized =
      if (couldBeScalaCli && range.getStart == range.getEnd)
        for {
          buffer <- buffers.get(path)
          line <- buffer.linesIterator
            .drop(range.getStart.getLine)
            .take(1)
            .headOption
          tree <- Trees.defaultTokenizerDialect(line).tokenize.toOption
        } yield tree
      else None

    tokenized
      .flatMap { tokens =>
        tokens
          .collectFirst {
            case comment: Comment
                if isScalaCliUsingDirectiveComment(comment.toString()) =>
              convertSbtToMillStyleIfPossible(comment.toString())
                .map(
                  buildAction(comment, kind, path, range.getStart.getLine)(_)
                )
                .toList
          }
      }
      .getOrElse(List.empty)
  }

}

object MillifyScalaCliDependencyCodeAction {

  private def buildAction(
      comment: Comment,
      kind: String,
      path: AbsolutePath,
      commentStartLine: Int,
  )(
      suggestion: ReplacementSuggestion
  ) = {
    val pos = new l.Range(
      new l.Position(
        comment.pos.startLine + commentStartLine,
        comment.pos.startColumn,
      ),
      new l.Position(
        comment.pos.endLine + commentStartLine,
        comment.pos.endColumn,
      ),
    )
    CodeActionBuilder.build(
      title = actionTitle(suggestion.millStyleDependency),
      kind = kind,
      changes = List(
        path -> List(
          new l.TextEdit(pos, suggestion.replacementText)
        )
      ),
    )
  }

  private def convertSbtToMillStyleIfPossible(
      sbtStyleDirective: String
  ): Option[ReplacementSuggestion] =
    sbtStyleDirective.split(" ").filterNot(_.isEmpty) match {
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
        Some(
          ReplacementSuggestion(dependencyIdentifierLike, millStyleDependency)
        )
      case _ => None
    }

  private val dependencyIdentifiers = Set("dep", "lib", "plugin")
  private val sbtDependencyDelimiters = Set("%", "%%", "%%%")

  private def actionTitle(millStyleDependency: String): String =
    s"""Convert to "$millStyleDependency""""

  private case class ReplacementSuggestion(
      dependencyIdentifier: String,
      millStyleDependency: String,
  ) {
    val replacementText: String =
      s"//> using $dependencyIdentifier \"$millStyleDependency\""
  }

  private[codeactions] def isScalaCliUsingDirectiveComment(
      text: String
  ): Boolean =
    text.split(" ").filterNot(_.isEmpty).toList match {
      case "//>" :: "using" :: _ => true
      case _ => false
    }

}
