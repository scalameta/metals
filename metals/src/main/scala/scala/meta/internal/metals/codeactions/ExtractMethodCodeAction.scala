package scala.meta.internal.metals.codeactions

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.Term
import scala.meta.Tree
import scala.meta.internal.metals.Buffers
import scala.meta.inputs.Position
import scala.meta.internal.metals.CodeAction
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.parsing.Trees
import scala.meta.internal.metals.ServerCommands
import scala.meta.tokens.Token

import scala.meta.pc.CancelToken

import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.{lsp4j => l}
import scala.meta.Defn
import scala.meta.Template
import scala.meta.Name

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

    // val toExtract: Option[Term.Apply] = {
    //   val tree: Option[Tree] = trees.get(path)
    //   def loop(t: Tree): Option[Term.Apply] = {
    //     t.children.find(_.pos.encloses(range)) match {
    //       case Some(child) =>
    //         loop(child)
    //       case None =>
    //         if (t.is[Term.Apply]) Some(t.asInstanceOf[Term.Apply]) else None
    //     }
    //   }
    //   tree.flatMap(loop(_))
    // }
    val applyOpt = trees.findLastEnclosingAt[Term.Apply](
      path,
      range.getStart(),
    )

    val edits = for {
      apply <- applyOpt
      stats <- lastEnclosingStatsList(apply)
      name = createNewName(stats)
      stat <- stats.find(stat => stat.pos.encloses(apply.pos))
      source <- buffers.get(path)
    } yield {
      val blank =
        if (source(stat.pos.start - stat.pos.startColumn) == '\t') '\t' else ' '
      val indent = blank.stringRepeat(indentationLength(source, stat.pos))
      val defText = s"${indent}def $name() = ${apply.toString()}"
      val defParamsOffset = defText.indexOf('(')
      val replacementText = s"$name()"
      val replacementParamsOffset =
        apply.pos.endColumn - apply.pos.startColumn + name.length + 1
      val paramsPos = new l.Position(
        apply.pos.endLine + 1,
        apply.pos.endColumn + replacementParamsOffset,
      )
      val defPos = new l.Position(stat.pos.endLine, defText.length-2)

      val replacedText = new l.TextEdit(apply.pos.toLSP, replacementText)
      val defEdit = withBraces(stat, defText)
      ((replacedText, paramsPos), (defEdit, defPos))
    }

    applyOpt
      .zip(edits)
      .map { case (apply, ((replacedText, paramsPos), (defEdit, defPos))) =>
        val codeAction = new l.CodeAction(ExtractMethodCodeAction.title)
        codeAction.setKind(l.CodeActionKind.RefactorExtract)
        codeAction.setEdit(
          new l.WorkspaceEdit(
            Map(path.toURI.toString -> Seq(replacedText, defEdit).asJava).asJava
          )
        )
        val applPos = new l.TextDocumentPositionParams(
          params.getTextDocument(),
          defPos    // TU ZAMIENIC NA RANGE, POTEM W COMPILERZE TEZ ZEBY BYLO COMPILERRANGEPARAMS
        )
        codeAction.setCommand(
          ServerCommands.ExtractMethod.toLSP(
            applPos
          )
        )
        Seq(codeAction)
      }
      .getOrElse(Nil)

  }

  private def withBraces(
      stat: Tree,
      valueString: String,
  ): l.TextEdit = {

    val range = stat.pos.toLSP
    val start = range.getStart()
    start.setCharacter(0)
    range.setEnd(start)
    new l.TextEdit(range, s"$valueString\n")
  }

  private def indentationLength(text: String, pos: Position): Int = {
    val lineStart = pos.start - pos.startColumn
    var i = lineStart
    while (i < text.length() && (text(i) == '\t' || text(i) == ' ')) {
      i += 1
    }
    i - lineStart
  }

  private def lastEnclosingStatsList(
      apply: Term.Apply
  ): Option[(List[Tree])] = {

    @tailrec
    def loop(tree: Tree): Option[List[Tree]] = {
      tree.parent match {
        case Some(t: Template) => Some(t.stats)
        case Some(b: Term.Block) => Some(b.stats)
        case Some(fy: Term.ForYield)
            if !fy.enums.headOption.exists(_.pos.encloses(apply.pos)) =>
          Some(fy.enums)
        case Some(f: Term.For) => Some(f.enums)
        case Some(df: Defn.Def) => Some(List(df.body))
        case Some(other) => loop(other)
        case None => None
      }
    }
    loop(apply)
  }

  private def createNewName(stats: Seq[Tree]): String = {

    // We don't want to use any name that is already being used in the scope
    def loop(t: Tree): List[String] = {
      t.children.flatMap {
        case n: Name => List(n.toString())
        case child => loop(child)
      }
    }
    val newValuePrefix = "newValue"
    val names = stats.flatMap(loop).toSet

    if (!names(newValuePrefix)) newValuePrefix
    else {
      var i = 0
      while (names(s"$newValuePrefix$i"))
        i += 1
      s"$newValuePrefix$i"
    }
  }
}

object ExtractMethodCodeAction {
  val title = "Extract method"
}
