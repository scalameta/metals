package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.Defn
import scala.meta.Pat
import scala.meta.Template
import scala.meta.Term
import scala.meta.Tree
import scala.meta.internal.metals.Compilers
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.logging
import scala.meta.internal.parsing.Trees
import scala.meta.pc.CancelToken

import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.{lsp4j => l}

class ExtractMethodCodeAction(
    trees: Trees,
    compilers: Compilers,
    languageClient: MetalsLanguageClient,
) extends CodeAction {

  override type CommandData = ServerCommands.ExtractMethodParams

  override def command: Option[ActionCommand] = Some(
    ServerCommands.ExtractMethod
  )
  override def kind: String = l.CodeActionKind.RefactorExtract

  override val maybeCodeActionId: Option[String] = Some(
    "ExtractMethod"
  )

  override def handleCommand(
      data: ServerCommands.ExtractMethodParams,
      token: CancelToken,
  )(implicit ec: ExecutionContext): Future[Unit] = {
    val doc = data.param
    val uri = doc.getUri()
    for {
      edits <- compilers.extractMethod(
        doc,
        data.range,
        data.extractPosition,
        token,
      )
      _ = logging.logErrorWhen(
        edits.isEmpty(),
        s"Could not extract method from range \n${data.range}\nin file ${uri.toAbsolutePath}",
      )
      workspaceEdit = new l.WorkspaceEdit(Map(uri -> edits).asJava)
      _ <- languageClient
        .applyEdit(new l.ApplyWorkspaceEditParams(workspaceEdit))
        .asScala
    } yield ()
  }

  override def contribute(params: CodeActionParams, token: CancelToken)(implicit
      ec: ExecutionContext
  ): Future[Seq[l.CodeAction]] = Future {
    val path = params.getTextDocument().getUri().toAbsolutePath
    val range = params.getRange()

    val toExtract: Option[List[Tree]] = {
      if (range.getStart() == range.getEnd()) {
        None
      } else {
        val tree: Option[Tree] = trees.get(path)
        def loop(expr: Tree): Option[Tree] = {
          expr.children.find(_.pos.encloses(range)) match {
            case Some(child) =>
              loop(child)
            case None =>
              Some(expr)
          }
        }
        val enclosing = tree.flatMap(loop(_))
        enclosing.map(_ match {
          case Term.Block(stats) =>
            stats.filter((s: Tree) => range.encloses(s.pos.toLsp))
          case Template(_, _, _, stats) =>
            stats.filter((s: Tree) => range.encloses(s.pos.toLsp))
          case ap if returnsValue(ap) => List(ap)
          case _ => Nil
        })
      }
    }

    val edits = {
      for {
        exprs <- toExtract
        expr <- exprs.lastOption
        head <- exprs.headOption if returnsValue(expr)
        scopes = enclosingList(head).flatMap(enclosingDef(_))
      } yield {
        scopes.map { case (defn, block) =>
          val defnPos =
            stats(block).find(_.pos.end >= head.pos.end).getOrElse(defn)
          val scopeName = defnTitle(defn)
          val exprRange = new l.Range(
            head.pos.toLsp.getStart(),
            expr.pos.toLsp.getEnd(),
          )
          val command = ServerCommands.ExtractMethod.toLsp(
            ServerCommands.ExtractMethodParams(
              params.getTextDocument(),
              exprRange,
              defnPos.pos.toLsp.getStart(),
            )
          )
          CodeActionBuilder.build(
            title = ExtractMethodCodeAction.title(scopeName),
            kind = this.kind,
            command = Some(command),
          )
        }
      }
    }.getOrElse(Nil)
    edits
  }

  private def defnTitle(defn: Tree): String = {
    defn match {
      case vl: Defn.Val =>
        vl.pats.head match {
          case Pat.Var(name) => s"val `${name}`"
          case _ => "val"
        }
      case vr: Defn.Var =>
        vr.pats.head match {
          case Pat.Var(name) => s"var `${name}`"
          case _ => "var"
        }
      case df: Defn.Def => s"method `${df.name}`"
      case cl: Defn.Class => s"class `${cl.name}`"
      case en: Defn.Enum => s"enum `${en.name}`"
      case ob: Defn.Object => s"object `${ob.name}`"
      case gv: Defn.Given => s"given `${gv.name}`"
      case ga: Defn.GivenAlias => s"given alias `${ga.name}`"
      case ec: Defn.EnumCase => s"enum case `${ec.name}`"
      case ma: Defn.Macro => s"macro `${ma.name}`"
      case ty: Defn.Type => s"type `${ty.name}`"
      case tr: Defn.Trait => s"trait `${tr.name}`"
      case _: Defn.RepeatedEnumCase => "reapeted enum case"
      case _: Defn.ExtensionGroup => "extension group"
      case _ => "block"
    }
  }

  private def returnsValue(t: Tree) = {
    t match {
      case _: Term.ApplyUnary => true
      case _: Term.Apply => true
      case _: Term.ApplyInfix => true
      case _: Term.Match => true
      case _: Term.If => true
      case _: Term.Throw => true
      case _: Term.Return => true
      case _ => false
    }
  }

  private def enclosingList(
      expr: Tree
  ): (List[Tree]) = {

    def loop(tree: Tree): List[Tree] = {
      tree.parent match {
        case Some(t: Template) => t :: loop(t)
        case Some(b: Term.Block) => b :: loop(b)
        case Some(other) => loop(other)
        case None => Nil
      }
    }
    loop(expr)
  }
  private def stats(t: Tree): List[Tree] = {
    t match {
      case t: Template => t.stats
      case b: Term.Block => b.stats
      case other => List(other)
    }
  }

  private def enclosingDef(
      expr: Tree
  ): Option[(Tree, Tree)] = {
    expr.parent match {
      case Some(d: Defn) => Some(d, expr)
      case _ => None
    }
  }
}

object ExtractMethodCodeAction {
  def title(scopeName: String): String = {
    s"Extract selection as method in $scopeName"
  }
}
