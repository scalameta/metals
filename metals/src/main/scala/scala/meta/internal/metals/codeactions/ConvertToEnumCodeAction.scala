package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.Defn
import scala.meta.Mod
import scala.meta.Tree
import scala.meta.internal.metals.Compilers
import scala.meta.internal.metals.JsonParser.XtensionSerializableToJson
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.parsing.Trees
import scala.meta.pc.CancelToken
import scala.meta.pc.CodeActionId

import org.eclipse.lsp4j
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.{lsp4j => l}

class ConvertToEnumCodeAction(
    trees: Trees,
    compilers: Compilers,
) extends CodeAction {

  case class ConvertToEnumCodeActionData(
      position: l.TextDocumentPositionParams
  ) extends CodeActionResolveData

  override def kind: String = l.CodeActionKind.RefactorRewrite

  override def contribute(params: CodeActionParams, token: CancelToken)(implicit
      ec: ExecutionContext
  ): Future[Seq[lsp4j.CodeAction]] = {
    val path = params.getTextDocument().getUri().toAbsolutePath
    if (
      compilers
        .supportedCodeActions(path)
        .asScala
        .contains(CodeActionId.ConvertToEnum)
    ) {
      val range = params.getRange()
      val maybeDefn = for {
        term <- trees.findLastEnclosingAt[Tree](
          path,
          range.getStart(),
          {
            case _: Defn.Class | _: Defn.Trait => true
            case _ => false
          },
        )
      } yield term

      def codeAction(name: String, isTrait: Boolean) =
        Future.successful(
          Seq(
            CodeActionBuilder.build(
              title = ConvertToEnumCodeAction.title(name, isTrait),
              kind = kind,
              data = Some(
                ConvertToEnumCodeActionData(
                  new l.TextDocumentPositionParams(
                    params.getTextDocument(),
                    params.getRange().getStart(),
                  )
                ).toJsonObject
              ),
            )
          )
        )

      maybeDefn match {
        case Some(d: Defn.Class)
            if d.name.pos
              .encloses(range) && d.mods.exists(_.isInstanceOf[Mod.Sealed]) =>
          codeAction(d.name.value, isTrait = false)
        case Some(d: Defn.Trait)
            if d.name.pos
              .encloses(range) && d.mods.exists(_.isInstanceOf[Mod.Sealed]) =>
          codeAction(d.name.value, isTrait = true)
        case _ => Future.successful(Seq())
      }
    } else Future.successful(Seq())
  }

  override def resolveCodeAction(
      codeAction: lsp4j.CodeAction,
      token: CancelToken,
  )(implicit ec: ExecutionContext): Option[Future[lsp4j.CodeAction]] =
    parseData[ConvertToEnumCodeActionData](codeAction) match {
      case Some(data) =>
        Some(
          compilers
            .codeAction(data.position, token, CodeActionId.ConvertToEnum, None)
            .map(edits => {
              val uri = data.position.getTextDocument().getUri()
              val workspaceEdit = new l.WorkspaceEdit(Map(uri -> edits).asJava)
              codeAction.setEdit(workspaceEdit)
              codeAction
            })
        )
      case None => None
    }

}

object ConvertToEnumCodeAction {
  def title(name: String, isTrait: Boolean): String =
    s"Convert sealed ${if (isTrait) "trait" else "class"} $name to enum."
}
