package scala.meta.internal.pc.completions

import scala.meta.internal.pc.CompletionValue
import scala.meta.internal.pc.Keyword

import dotty.tools.dotc.ast.tpd.Block
import dotty.tools.dotc.ast.tpd.DefDef
import dotty.tools.dotc.ast.tpd.Ident
import dotty.tools.dotc.ast.tpd.PackageDef
import dotty.tools.dotc.ast.tpd.Template
import dotty.tools.dotc.ast.tpd.TermTree
import dotty.tools.dotc.ast.tpd.Tree
import dotty.tools.dotc.ast.tpd.TypeDef
import dotty.tools.dotc.ast.tpd.ValDef
import dotty.tools.dotc.ast.tpd.ValOrDefDef
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.util.SourcePosition
import org.eclipse.{lsp4j as l}

object KeywordsCompletions:

  def contribute(
      pos: SourcePosition,
      path: List[Tree]
  )(using ctx: Context): List[CompletionValue] =

    lazy val notInComment = checkIfNotInComment(pos, path)
    getIdentifierName(path, pos) match
      case None =>
        Keyword.all.collect {
          case kw if kw.isPackage && path == Nil && notInComment =>
            CompletionValue.keyword(kw.name, kw.insertText)
        }
      case Some(name) =>
        val isExpression = this.isExpression(path)
        val isBlock = this.isBlock(path)
        val isDefinition = this.isDefinition(path, name, pos)
        val isMethodBody = this.isMethodBody(path)
        val isTemplate = this.isTemplate(path)
        val isPackage = this.isPackage(path)
        val isParam = this.isParam(path)
        Keyword.all.collect {
          case kw
              if kw.matchesPosition(
                name,
                isExpression = isExpression,
                isBlock = isBlock,
                isDefinition = isDefinition,
                isMethodBody = isMethodBody,
                isTemplate = isTemplate,
                isPackage = isPackage,
                isParam = isParam,
                isScala3 = true,
                allowToplevel = true
              ) && notInComment =>
            CompletionValue.keyword(kw.name, kw.insertText)
        }
    end match
  end contribute

  private def checkIfNotInComment(
      pos: SourcePosition,
      path: List[Tree]
  ): Boolean =
    import scala.meta.*
    val content = pos.source.content
    val text = path.headOption
      .map(t => content.slice(t.span.start, t.span.end))
      .getOrElse(content)
    val start = pos.start
    val end = pos.end
    val tokens = text.tokenize.toOption
    val treeStart = path.headOption.map(_.span.start).getOrElse(0)
    tokens
      .flatMap(t =>
        t.find {
          case t: Token.Comment
              if treeStart + t.pos.start < start && treeStart + t.pos.end >= end =>
            true
          case _ => false
        }
      )
      .isEmpty
  end checkIfNotInComment

  private def getIdentifierName(
      enclosing: List[Tree],
      pos: SourcePosition
  ): Option[String] =
    enclosing match
      case Ident(name) :: _ => Some(name.toString())
      case (vd: ValDef) :: _ => Some(vd.name.toString())
      case _ =>
        getLeadingIdentifierText(pos)

  private def getLeadingIdentifierText(pos: SourcePosition): Option[String] =
    var i = pos.point - 1
    val chars = pos.source.content
    while i >= 0 && !chars(i).isWhitespace do i -= 1
    if i < 0 then None
    else Some(new String(chars, i + 1, pos.point - i - 1))

  private def isPackage(enclosing: List[Tree]): Boolean =
    enclosing match
      case Nil => true
      case _ => false

  private def isParam(enclosing: List[Tree]): Boolean =
    enclosing match
      case (vd: ValDef) :: (dd: DefDef) :: _
          if dd.paramss.exists(pc => pc.contains(vd) && pc.size == 1) =>
        true
      case _ => false

  private def isTemplate(enclosing: List[Tree]): Boolean =
    enclosing match
      case Ident(_) :: (_: Template) :: _ => true
      case Ident(_) :: (_: ValOrDefDef) :: _ => true
      case (_: TypeDef) :: _ => true
      case _ => false

  private def isMethodBody(enclosing: List[Tree]): Boolean =
    enclosing match
      case Ident(_) :: (_: DefDef) :: _ => true
      case _ => false

  private def isDefinition(
      enclosing: List[Tree],
      name: String,
      pos: SourcePosition
  )(using ctx: Context): Boolean =
    enclosing match
      case (_: Ident) :: _ => false
      case _ =>
        // NOTE(olafur) in positions like "implicit obje@@" the parser discards the entire
        // statement and `enclosing` is not helpful. In these situations we fallback to the
        // diagnostics reported by the parser to see if it expected a definition here.
        // This is admittedly not a great solution, but it's the best I can think of at this point.
        val point = pos.withSpan(pos.span.withPoint(pos.point - name.length()))

        val isExpectedStartOfDefinition =
          ctx.reporter.allErrors.exists { info =>
            info.pos.focus == point &&
            info.message == "expected start of definition"
          }
        isExpectedStartOfDefinition

  private def isBlock(enclosing: List[Tree]): Boolean =
    enclosing match
      case Ident(_) :: Block(_, _) :: _ => true
      case _ => false

  private def isExpression(enclosing: List[Tree]): Boolean =
    enclosing match
      case Ident(_) :: (_: Template) :: _ => true
      case Ident(_) :: (_: ValOrDefDef) :: _ => true
      case Ident(_) :: t :: _ if t.isTerm => true
      case other => false
end KeywordsCompletions
