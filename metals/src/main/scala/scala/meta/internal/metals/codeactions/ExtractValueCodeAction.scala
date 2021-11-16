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
import scala.meta.internal.trees.Origin.Parsed
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
      argument <- apply.args.find { arg => arg.pos.encloses(range) }
      // avoid extracting lambdas (this needs actual type information)
      if isNotLambda(argument)
      stat <- stats.find(stat => stat.pos.encloses(apply.pos))
      name = createNewName(stats)
      source <- buffers.get(path)
      blank =
        if (source(stat.pos.start - stat.pos.startColumn) == '\t') '\t' else ' '
      lineStart = stat.pos.start - stat.pos.startColumn
      indent = blank.stringRepeat(indentationLength(source, lineStart))
      keyword = if (stat.isInstanceOf[Enumerator]) "" else "val "
      // we will inset `val newValue = ???` before the existing statement containing apply
      valueText = s"${indent}$keyword$name = ${argument.toString()}"
      valueTextWithBraces = withBraces(stat, source, valueText, blank)
      // we need to add additional () in case of  `apply{}`
      replacementText =
        if (argument.is[Term.Block] && !applyHasParens(apply)) s"($name)"
        else name
      replacedArgument = new l.TextEdit(argument.pos.toLSP, replacementText)
    } yield valueTextWithBraces :+ replacedArgument

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
      defnLineIndentation = blank.stringRepeat(
        indentationLength(source, defn.pos.start - defn.pos.startColumn)
      )
      additionalIndent =
        if (defnLineIndentation.headOption.exists(_ == '\t')) "\t"
        else "  "
      innerIndentation = defnLineIndentation + additionalIndent
    } yield {
      val statStart = stat.pos.toLSP
      statStart.setEnd(statStart.getStart())

      val startBlockPos = equalsPos.toLSP
      startBlockPos.setStart(startBlockPos.getEnd())
      startBlockPos.setEnd(statStart.getStart())

      val noIndentation = equalsPos.startLine == defn.body.pos.startLine

      // Scala 3 optional braces
      if (canUseBracelessSyntax(stat, source)) {
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
          val statIndentation =
            indentationLength(source, stat.pos.start - stat.pos.startColumn)
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
        // Scala 2
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

    // no braces are needed
    edits.getOrElse {
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

  private def indentationLength(text: String, lineStart: Int): Int = {
    var i = lineStart
    while (i < text.length() && (text(i) == '\t' || text(i) == ' ')) {
      i += 1
    }
    i - lineStart
  }

  /**
   * Check if it's possible to use braceless syntax and whther
   * it's the preffered style in the file.
   */
  private def canUseBracelessSyntax(stat: Tree, source: String) = {

    def allowBracelessSyntax(tree: Tree) = tree.origin match {
      case p: Parsed => p.dialect.allowSignificantIndentation
      case _ => false
    }

    // Let's try to use the style of any existing parent.
    def existsBracelessParent(tree: Tree): Boolean = {
      tree.parent match {
        case Some(t @ (_: Template | _: Term.Block)) =>
          source(t.pos.start) != '{'
        case Some(other) => existsBracelessParent(other)
        case None => existsBracelessChild(tree)
      }
    }

    // If we are at the top, let's check also the siblings
    def existsBracelessChild(tree: Tree): Boolean = {
      tree.children.exists {
        case t @ (_: Template | _: Term.Block) =>
          source(t.pos.start) != '{'
        case other =>
          existsBracelessChild(other)
      }
    }

    allowBracelessSyntax(stat) && existsBracelessParent(stat)
  }

  private def isNotLambda(tree: Tree): Boolean = {

    def hasPlaceholder(tree: Tree): Boolean = {
      tree match {
        case _: Term.Placeholder => true
        /**
         * Placeholder in the next apply no longer applies to the current apply.
         * ApplyInfix or ApplyUnary cannot be extracted though by themselves without types.
         */
        case _: Term.Apply => false
        case _: Term.ApplyUsing => false
        case _ =>
          tree.children.exists(hasPlaceholder)
      }
    }

    tree match {
      case _: Term.FunctionTerm => false
      case _: Term.PolyFunction => false
      case _: Term.PartialFunction => false
      case Term.Block(List(single)) => isNotLambda(single)
      case _ => !hasPlaceholder(tree)
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
        case Some(fy: Term.ForYield) => Some(fy.enums)
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
