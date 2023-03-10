package scala.meta.internal.pc.completions

import scala.meta.internal.mtags.MtagsEnrichments.given
import scala.meta.internal.pc.Keyword
import scala.meta.tokenizers.XtensionTokenizeInputLike
import scala.meta.tokens.Token

import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.util.SourcePosition
import org.eclipse.{lsp4j as l}

object KeywordsCompletions:

  def contribute(
      path: List[Tree],
      completionPos: CompletionPos,
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
        val isSelect = this.isSelect(path)
        val isImport = this.isImport(path)
        val templateKeywordAvailability =
          checkTemplateForNewParents(path, completionPos)
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
                isSelect = isSelect,
                isImport = isImport,
                canBeExtended = templateKeywordAvailability.`extends`,
                canDerive = templateKeywordAvailability.`derives`,
                hasExtend = templateKeywordAvailability.`with`,
                allowToplevel = true,
              ) && notInComment =>
            CompletionValue.keyword(kw.name, kw.insertText)
        }
    end match
  end contribute

  private def checkIfNotInComment(
      pos: SourcePosition,
      path: List[Tree],
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
      case (_: PackageDef) :: _ => true
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

  private def isSelect(enclosing: List[Tree]): Boolean =
    enclosing match
      case (_: Apply) :: (_: Select) :: _ => true
      case (_: Select) :: _ => true
      case _ => false

  private def isImport(enclosing: List[Tree]): Boolean =
    enclosing match
      case Import(_, _) :: _ => true
      case _ => false

  private def isDefinition(
      enclosing: List[Tree],
      name: String,
      pos: SourcePosition,
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

  case class TemplateKeywordAvailability(
      `extends`: Boolean,
      `with`: Boolean,
      `derives`: Boolean,
  )

  /*
   * Checks whether given path and position can be followed with
   * `extends`, `with` or `derives` keywords.
   * @param enclosing - typed path to position
   * @param pos - completion position
   *
   * @returns TemplateKeywordAvailability with available keywords
   *
   * @note This method requires a typed path. The rest of the metals functionalities.
   */
  def checkTemplateForNewParents(enclosing: List[Tree], pos: CompletionPos)(
      using ctx: Context
  ): TemplateKeywordAvailability =

    /*
     * Finds tree which ends just before cursor positions, that may be extended or derive.
     * In Scala 3, such tree must be a `TypeDef` which has field of type `Template` describing
     * its parents and derived classes.
     *
     * @tree - enclosing tree
     *
     * @returns TypeDef tree defined before the cursor position or `enclosingTree` otherwise
     */
    def findLastSatisfyingTree(enclosingTree: Tree): Option[Tree] =
      enclosingTree match
        // for Enum, the enclosing tree is correct and contains cursor position in its span
        case typeDef: TypeDef if typeDef.symbol.flags.is(Flags.Enum) =>
          Some(typeDef)
        case other =>
          val typeDefs = other.filterSubTrees {
            // package test
            // class Test ext@@ - Interactive.pathTo returns `PackageDef` instead of `TypeDef`
            // - because it tried to repair the broken tree by finishing `TypeDef` before ext
            //
            // The cursor position is 27 and tree positions after parsing are:
            //
            //  package Test@../Test.sc<8..12> {
            //    class Test {}@../Test.sc[13..19..23]
            //  }@../Test.sc<0..27>
            case typeDef: TypeDef =>
              typeDef.span.exists && typeDef.span.end < pos.sourcePos.span.start
            case other => false
          }

          typeDefs match
            // If we didn't find any trees, it means the enclosingTree is not a TypeDef,
            // thus can't be followed with `extends`, `with` and `derives`
            case Nil => None
            case other =>
              other
                .filter(tree => tree.span.exists && tree.span.end < pos.start)
                .maxByOption(_.span.end)

    end findLastSatisfyingTree

    enclosing.headOption
      .flatMap { enclosingTree =>
        findLastSatisfyingTree(enclosingTree).map {
          case tpeDef @ TypeDef(_, template: Template) if tpeDef.isClassDef =>
            if template.parents.forall(parent =>
                parent.symbol.exists && (
                  parent.symbol.owner == ctx.definitions.ObjectClass ||
                    parent.symbol == ctx.definitions.ObjectClass ||
                    parent.symbol == ctx.definitions.EnumClass
                )
              )
            then TemplateKeywordAvailability(true, false, true)
            else TemplateKeywordAvailability(false, true, true)
          case other => TemplateKeywordAvailability(false, false, false)
        }
      }
      .getOrElse(TemplateKeywordAvailability(false, false, false))

  end checkTemplateForNewParents

end KeywordsCompletions
