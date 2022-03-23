package scala.meta.internal.metals.codeactions

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.Defn
import scala.meta.Enumerator
import scala.meta.Name
import scala.meta.Template
import scala.meta.Term
import scala.meta.Tree
import scala.meta.inputs.Position
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.CodeAction
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.parsing.Trees
import scala.meta.pc.CancelToken
import scala.meta.tokens.Token

import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.{lsp4j => l}

class ExtractValueCodeAction(
    trees: Trees,
    buffers: Buffers
) extends CodeAction {
  override def kind: String = l.CodeActionKind.RefactorExtract

  override def contribute(params: CodeActionParams, token: CancelToken)(implicit
      ec: ExecutionContext
  ): Future[Seq[l.CodeAction]] = Future {
    val path = params.getTextDocument().getUri().toAbsolutePath
    val range = params.getRange()

    val applyOpt = trees.findLastEnclosingAt[Term.Apply](
      path,
      range.getStart(),
      appl => appl.args.exists(_.pos.encloses(range))
    )

    val textEdits = for {
      apply <- applyOpt
      stats <- lastEnclosingStatsList(apply)
      argumentOpt <- apply.args.find { arg => arg.pos.encloses(range) }
      argument = argumentOpt match {
        // named paramaeter
        case Term.Assign(_, rhs) => rhs
        case other => other
      }
      // avoid extracting lambdas (this needs actual type information)
      if isNotLambda(argument)
      stat <- stats.find(stat => stat.pos.encloses(apply.pos))
      name = createNewName(stats)
      source <- buffers.get(path)
    } yield {
      val blank =
        if (source(stat.pos.start - stat.pos.startColumn) == '\t') '\t' else ' '
      val indent = blank.stringRepeat(indentationLength(source, stat.pos))
      val keyword = if (stat.isInstanceOf[Enumerator]) "" else "val "
      // we will insert `val newValue = ???` before the existing statement containing apply
      val valueText = s"${indent}$keyword$name = ${argument.toString()}"
      val valueTextWithBraces = withBraces(stat, source, valueText, blank)
      // we need to add additional () in case of  `apply{}`
      val replacementText =
        if (argument.is[Term.Block] && !applyHasParens(apply)) s"($name)"
        else name
      val replacedArgument = new l.TextEdit(argument.pos.toLSP, replacementText)
      valueTextWithBraces :+ replacedArgument
    }
    textEdits match {
      case Some(edits) =>
        val codeAction = new l.CodeAction()
        codeAction.setTitle(ExtractValueCodeAction.title)
        codeAction.setKind(this.kind)
        codeAction.setEdit(
          new l.WorkspaceEdit(
            Map(path.toURI.toString -> edits.asJava).asJava
          )
        )
        Seq(codeAction)
      case None => Nil
    }
  }

  /**
   * If statement's direct parent is Def it means it's a
   * single line method and we need to add braces.
   *
   * @param stat statement we are extracting from
   * @param source full text of the file
   * @param valueString extracted value to add
   * @param blank whietespace character to use for indentation
   * @return text edits together with braces to add
   */
  private def withBraces(
      stat: Tree,
      source: String,
      valueString: String,
      blank: Char
  ): Seq[l.TextEdit] = {

    def defnEqualsPos(defn: Defn.Def): Option[Position] = defn.tokens.reverse
      .collectFirst {
        case t: Token.Equals if t.start < defn.body.pos.start => t.pos
      }

    val edits = for {
      defn <- stat.parent.collect { case defn: Defn.Def => defn }
      equalsPos <- defnEqualsPos(defn)
    } yield {
      val defnLineIndentation =
        blank.stringRepeat(indentationLength(source, defn.pos))
      val additionalIndent =
        if (defnLineIndentation.headOption.contains('\t')) "\t"
        else "  "
      val innerIndentation = defnLineIndentation + additionalIndent
      val statStart = stat.pos.toLSP
      statStart.setEnd(statStart.getStart())

      val startBlockPos = equalsPos.toLSP
      startBlockPos.setStart(startBlockPos.getEnd())
      startBlockPos.setEnd(statStart.getStart())

      val noIndentation = equalsPos.startLine == defn.body.pos.startLine

      // Scala 3 optional braces
      if (stat.canUseBracelessSyntax(source)) {
        // we need to create a new indented region
        if (noIndentation) {
          Seq(
            new l.TextEdit(
              startBlockPos,
              s"""|
                  |$additionalIndent$valueString
                  |$innerIndentation""".stripMargin
            )
          )
        }
        // make sure existing indentation after `=` is correct
        else {
          val statIndentation = indentationLength(source, stat.pos)
          val statAdditionalIndentation =
            if (statIndentation <= defnLineIndentation.size)
              (defnLineIndentation.size - statIndentation) + additionalIndent.size
            else 0
          val indentStat = blank.stringRepeat(statAdditionalIndentation)

          Seq(
            new l.TextEdit(
              statStart,
              s"""|$indentStat${valueString.trim()}
                  |$innerIndentation""".stripMargin
            )
          )
        }
        // Scala 2 and Scala 3 non signification whitespace syntax
      } else {
        val startBlockText =
          // we should indent the stat
          if (noIndentation)
            s"""| {
                |$additionalIndent$valueString
                |$innerIndentation""".stripMargin
          // stat should alredy be indented correctly
          else
            s"""| {
                |$valueString
                |$innerIndentation""".stripMargin

        val startBlockEdit =
          new l.TextEdit(startBlockPos, startBlockText)
        val endBracePos = defn.pos.toLSP
        endBracePos.setStart(endBracePos.getEnd())
        val endBraceEdit =
          new l.TextEdit(endBracePos, s"\n$defnLineIndentation}")
        Seq(startBlockEdit, endBraceEdit)
      }
    }

    edits.getOrElse {
      // otherwise, no braces are needed
      val range = stat.pos.toLSP
      val start = range.getStart()
      start.setCharacter(0)
      range.setEnd(start)
      Seq(new l.TextEdit(range, s"$valueString\n"))
    }

  }

  /**
   * `(` is contained between function and it's arguments
   */
  private def applyHasParens(apply: Term.Apply) = {
    apply.tokens.exists { t =>
      t.is[Token.LeftParen] &&
      t.pos.start >= apply.fun.pos.end &&
      apply.args.headOption.forall(_.pos.start >= t.pos.end)
    }
  }

  private def indentationLength(text: String, pos: Position): Int = {
    val lineStart = pos.start - pos.startColumn
    var i = lineStart
    while (i < text.length() && (text(i) == '\t' || text(i) == ' ')) {
      i += 1
    }
    i - lineStart
  }

  @tailrec
  private def isNotLambda(tree: Tree): Boolean = {

    tree match {
      case _: Term.FunctionTerm => false
      case _: Term.PolyFunction => false
      case _: Term.PartialFunction => false
      case _: Term.AnonymousFunction => false
      case Term.Block(List(single)) => isNotLambda(single)
      case _ => true
    }
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

object ExtractValueCodeAction {
  val title = "Extract value"
}
