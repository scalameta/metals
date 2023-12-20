package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.Term
import scala.meta.Tree
import scala.meta.given
import scala.meta.internal.metals.Compilers
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.logging
import scala.meta.internal.parsing.Trees
import scala.meta.pc.CancelToken

import org.eclipse.{lsp4j => l}

class ConvertToNamedArguments(
    trees: Trees,
    compilers: Compilers,
    languageClient: MetalsLanguageClient,
) extends CodeAction {

  import ConvertToNamedArguments._
  override val kind: String = l.CodeActionKind.RefactorRewrite

  override type CommandData = ServerCommands.ConvertToNamedArgsRequest

  override def command: Option[ActionCommand] = Some(
    ServerCommands.ConvertToNamedArguments
  )

  override def handleCommand(
      data: ServerCommands.ConvertToNamedArgsRequest,
      token: CancelToken,
  )(implicit ec: ExecutionContext): Future[Unit] = {
    val uri = data.position.getTextDocument().getUri()
    for {
      edits <- compilers.convertToNamedArguments(
        data.position,
        data.argIndices.toList.asJava,
        token,
      )
      _ = logging.logErrorWhen(
        edits.isEmpty(),
        s"Could not find the correct names for arguments at ${data.position} with indices ${data.argIndices.toList
            .mkString(",")}",
      )
      workspaceEdit = new l.WorkspaceEdit(Map(uri -> edits).asJava)
      _ <- languageClient
        .applyEdit(new l.ApplyWorkspaceEditParams(workspaceEdit))
        .asScala
    } yield ()
  }

  def getTermWithArgs(
      apply: Term,
      args: List[Tree],
  ): Option[ApplyTermWithArgIndices] = {
    val argIndices = args.zipWithIndex.collect {
      case (arg, index)
          if !arg.isInstanceOf[Term.Assign] && !arg
            .isInstanceOf[Term.Block] =>
        index
    }
    if (argIndices.isEmpty) firstApplyWithUnnamedArgs(apply.parent)
    else Some(ApplyTermWithArgIndices(apply, argIndices))
  }

  def firstApplyWithUnnamedArgs(
      term: Option[Tree]
  ): Option[ApplyTermWithArgIndices] = {
    term match {
      case Some(apply: Term.Apply) =>
        getTermWithArgs(apply, apply.args)
      case Some(newAppl @ Term.New(init)) =>
        getTermWithArgs(newAppl, init.argss.flatten)
      case Some(t) => firstApplyWithUnnamedArgs(t.parent)
      case _ => None
    }
  }

  private def methodName(t: Term, isFirst: Boolean = false): String = {
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
      case Term.New(init) =>
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
    def predicate(t: Term): Boolean =
      t match {
        case Term.Apply(fun, _) => !fun.pos.encloses(range)
        case Term.New(init) => init.pos.encloses(range)
        case _ => false
      }
    val maybeApply = for {
      term <- trees.findLastEnclosingAt[Term](
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
          val command =
            ServerCommands.ConvertToNamedArguments.toLsp(
              ServerCommands
                .ConvertToNamedArgsRequest(
                  position,
                  apply.argIndices.map(new Integer(_)).toArray,
                )
            )

          val codeAction = CodeActionBuilder.build(
            title = title(methodName(apply.app, isFirst = true)),
            kind = l.CodeActionKind.RefactorRewrite,
            command = Some(command),
          )

          Future.successful(Seq(codeAction))
        }
      }
      .getOrElse(Future.successful(Nil))

  }
}

object ConvertToNamedArguments {
  case class ApplyTermWithArgIndices(app: Term, argIndices: List[Int])
  def title(funcName: String): String =
    s"Convert '$funcName' to named arguments"
}
