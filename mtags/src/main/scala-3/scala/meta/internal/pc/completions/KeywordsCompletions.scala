package scala.meta.internal.pc.completions

import scala.meta.internal.mtags.MtagsEnrichments.given
import scala.meta.internal.pc.CompletionPos
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
      path: List[Tree],
      completionPos: CompletionPos
  )(using ctx: Context): List[CompletionValue] =

    lazy val notInComment = checkIfNotInComment(completionPos.cursorPos, path)
    path match
      case Nil if completionPos.query.isEmpty =>
        Keyword.all.collect {
          // topelevel definitions are allowed in Scala 3
          case kw if (kw.isPackage || kw.isTemplate) && notInComment =>
            CompletionValue.keyword(kw.name, kw.insertText)
        }
      case _ =>
        val isExpression = this.isExpression(path)
        val isBlock = this.isBlock(path)
        val isDefinition =
          this.isDefinition(path, completionPos.query, completionPos.cursorPos)
        val isMethodBody = this.isMethodBody(path)
        val isTemplate = this.isTemplate(path)
        val isPackage = this.isPackage(path)
        val isParam = this.isParam(path)
        Keyword.all.collect {
          case kw
              if kw.matchesPosition(
                completionPos.query,
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
    val text = pos.source.content
    val (treeStart, treeEnd) = path.headOption
      .map(t => (t.span.start, t.span.end))
      .getOrElse((0, text.size))
    val offset = pos.start
    text.mkString.checkIfNotInComment(treeStart, treeEnd, offset)
  end checkIfNotInComment

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
