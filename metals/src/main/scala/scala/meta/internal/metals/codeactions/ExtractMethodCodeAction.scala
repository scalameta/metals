package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.Term
import scala.meta.Tree
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.CodeAction
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.parsing.Trees
import scala.meta.internal.metals.ServerCommands

import scala.meta.pc.CancelToken

import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.{lsp4j => l}
import scala.meta.Defn

class ExtractMethodCodeAction(
    trees: Trees,
    buffers: Buffers,
) extends CodeAction {
  override def kind: String = l.CodeActionKind.RefactorExtract
  override def contribute(params: CodeActionParams, token: CancelToken)(implicit
      ec: ExecutionContext
  ): Future[Seq[l.CodeAction]] = Future {
    val path = params.getTextDocument().getUri().toAbsolutePath
    val range = params.getRange()
    
    
    val toExtract: Option[Term.Apply] = {
      val tree: Option[Tree] = trees.get(path)
      def loop(t: Tree): Option[Term.Apply] = {
        t.children.find(_.pos.encloses(range)) match {
          case Some(child) => 
            loop(child)
          case None => if(t.is[Term.Apply]) Some(t.asInstanceOf[Term.Apply]) else None
        }
      }
      tree.flatMap(loop(_)) 
    }



    toExtract.map { apply =>
      val codeAction = new l.CodeAction(ExtractMethodCodeAction.title)
      codeAction.setKind(l.CodeActionKind.RefactorExtract)
      val applPos = new l.TextDocumentPositionParams(
        params.getTextDocument(),
        new l.Position(apply.pos.endLine, apply.pos.endColumn),
      )
      codeAction.setCommand(
        ServerCommands.ExtractMethod.toLSP(
          applPos
        )
      )
      Seq(codeAction)
    }.getOrElse(Nil)
  
  }
}

object ExtractMethodCodeAction {
  val title = "Extract method"
}
