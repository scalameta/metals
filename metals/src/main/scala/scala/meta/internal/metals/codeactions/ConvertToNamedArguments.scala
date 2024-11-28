package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.Init
import scala.meta.Term
import scala.meta.Tree
import scala.meta.XtensionSyntax
import scala.meta.internal.metals.Compilers
import scala.meta.internal.metals.JsonParser.XtensionSerializableToJson
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.codeactions.CodeAction
import scala.meta.internal.metals.codeactions.CodeActionBuilder
import scala.meta.internal.metals.logging
import scala.meta.internal.parsing.Trees
import scala.meta.pc.CancelToken

import org.eclipse.{lsp4j => l}

class ConvertToNamedArguments(
    trees: Trees,
    compilers: Compilers,
) extends CodeAction {

  import ConvertToNamedArguments._

  case class ConvertToNamedArgsData(
      position: l.TextDocumentPositionParams,
      argIndices: java.util.List[Integer],
  ) extends CodeActionResolveData

  override val kind: String = l.CodeActionKind.RefactorRewrite

  override def resolveCodeAction(codeAction: l.CodeAction, token: CancelToken)(
      implicit ec: ExecutionContext
  ): Option[Future[l.CodeAction]] = {
    parseData[ConvertToNamedArgsData](codeAction) match {
      case Some(data) =>
        val uri = data.position.getTextDocument().getUri()
        val result = for {
          edits <- compilers.convertToNamedArguments(
            data.position,
            data.argIndices,
            token,
          )
          _ = logging.logErrorWhen(
            edits.isEmpty(),
            s"Could not find the correct names for arguments at ${data.position} with indices ${data.argIndices.asScala
                .mkString(",")}",
          )
          workspaceEdit = new l.WorkspaceEdit(Map(uri -> edits).asJava)
          _ = codeAction.setEdit(workspaceEdit)
        } yield codeAction
        Some(result)
      case None => None
    }
  }

  private def getTermWithArgs(
      apply: Tree,
      args: List[Tree],
      nameEnd: Int,
  ): Option[ApplyTermWithArgIndices] = {
    args match {
      case single :: Nil if single.isInstanceOf[Term.Block] =>
        val adjustedNameEnd = nameEnd - apply.pos.start
        val adjustedArgStart = single.pos.start - apply.pos.start
        val preBlock = apply.text.slice(adjustedNameEnd, adjustedArgStart)
        if (preBlock.toCharArray().contains('('))
          Some(ApplyTermWithArgIndices(apply, List(0)))
        else None
      case _ =>
        val argIndices = args.zipWithIndex.collect {
          case (arg, index) if !arg.isInstanceOf[Term.Assign] =>
            index
        }
        if (argIndices.isEmpty) firstApplyWithUnnamedArgs(apply.parent)
        else Some(ApplyTermWithArgIndices(apply, argIndices))
    }
  }

  private def firstApplyWithUnnamedArgs(
      term: Option[Tree]
  ): Option[ApplyTermWithArgIndices] = {
    term match {
      case Some(apply: Term.Apply) =>
        getTermWithArgs(apply, apply.argClause.values, apply.fun.pos.end)
      case Some(init: Init) =>
        getTermWithArgs(
          init,
          init.argClauses.flatMap(_.values).toList,
          init.name.pos.end,
        )
      case Some(t) => firstApplyWithUnnamedArgs(t.parent)
      case _ => None
    }
  }

  private def methodName(t: Tree, isFirst: Boolean = false): String = {
    t match {
      // a.foo(a)
      case Term.Select(_, name) =>
        name.value
      // foo(a)(b@@)
      case Term.Apply(fun, _) if isFirst =>
        methodName(fun) + "(...)"
      // foo(a@@)(b)
      case appl: Term.Apply =>
        methodName(appl.fun) + "()"
      // foo(a)
      case Term.Name(name) =>
        name
      case init: Init =>
        init.tpe.syntax + "(...)"
      case _ =>
        t.syntax
    }
  }

  override def contribute(params: l.CodeActionParams, token: CancelToken)(
      implicit ec: ExecutionContext
  ): Future[Seq[l.CodeAction]] = {

    val path = params.getTextDocument().getUri().toAbsolutePath
    val range = params.getRange()
    def predicate(t: Tree): Boolean =
      t match {
        case Term.Apply(fun, _) => !fun.pos.encloses(range)
        case _: Init => true
        case _ => false
      }
    val maybeApply = for {
      term <- trees.findLastEnclosingAt[Tree](
        path,
        range.getStart(),
        term => predicate(term),
      )
      apply <- firstApplyWithUnnamedArgs(Some(term))
    } yield apply

    maybeApply
      .map { apply =>
        {
          val position = new l.TextDocumentPositionParams(
            params.getTextDocument(),
            new l.Position(apply.app.pos.endLine, apply.app.pos.endColumn),
          )
          val data =
            ConvertToNamedArgsData(
              position,
              apply.argIndices.map(Integer.valueOf).asJava,
            )

          val codeAction = CodeActionBuilder.build(
            title = title(methodName(apply.app, isFirst = true)),
            kind = l.CodeActionKind.RefactorRewrite,
            data = Some(data.toJsonObject),
          )

          Future.successful(Seq(codeAction))
        }
      }
      .getOrElse(Future.successful(Nil))

  }
}

object ConvertToNamedArguments {
  case class ApplyTermWithArgIndices(app: Tree, argIndices: List[Int])
  def title(funcName: String): String =
    s"Convert '$funcName' to named arguments"
}
