package scala.meta.internal.pc

import scala.meta._
import scala.meta.pc.OffsetParams
import scala.meta.tokens.{Token => T}

import org.eclipse.lsp4j.TextEdit
import org.eclipse.{lsp4j => l}

/**
 * Tries to calculate edits needed to insert the inferred type annotation
 * in all the places that it is possible such as:
 * - value or variable declaration
 * - methods
 * - pattern matches
 * - for comprehensions
 * - lambdas
 *
 * The provider will not check if the type does not exist, since there is no way to
 * get that data from the presentation compiler. The actual check is being done via
 * scalameta parser in InsertInferredType code action.
 *
 * @param compiler Metals presentation compiler
 * @param params position and actual source
 */
final class InferredTypeProvider(
    val compiler: MetalsGlobal,
    params: OffsetParams
) {
  import compiler._

  case class AdjustTypeOpts(
      text: String,
      adjustedEndPos: l.Position
  )

  def inferredTypeEdits(
      adjustOpt: Option[AdjustTypeOpts] = None
  ): List[TextEdit] = {
    val retryType = adjustOpt.isEmpty
    val sourceText = adjustOpt.map(_.text).getOrElse(params.text())

    val unit = addCompilationUnit(
      code = sourceText,
      filename = params.uri().toString(),
      cursor = None
    )

    val pos = unit.position(params.offset)
    val typedTree = typedTreeAt(pos)
    val importPosition = autoImportPosition(pos, params.text())
    val context = doLocateImportContext(pos)
    val re: scala.collection.Map[Symbol, Name] = renamedSymbols(context)
    val history = new ShortenedNames(
      lookupSymbol = name =>
        context.lookupSymbol(name, sym => !sym.isStale) :: Nil,
      config = renameConfig,
      renames = re
    )

    def additionalImports = importPosition match {
      case None =>
        // No import position means we can't insert an import without clashing with
        // existing symbols in scope, so we just do nothing
        Nil
      case Some(importPosition) =>
        history.autoImports(pos, importPosition)
    }

    def prettyType(tpe: Type) =
      metalsToLongString(tpe.widen.finalResultType, history)

    def findNameEnd(start: Int, name: Name): Int = {
      // dropLocal will remove a space that might appear at the of a name in some places
      val identLength = name.dropLocal.length
      val backtickInc = if (params.text().charAt(start) == '`') 2 else 0
      start + identLength + backtickInc
    }

    def removeType(nameEnd: Int, tptEnd: Int) = {
      sourceText.substring(0, nameEnd) +
        sourceText.substring(tptEnd + 1, sourceText.length())
    }

    def adjustType(rhs: Tree, tpt: Tree, lastTokenPos: Int): List[TextEdit] = {
      // if type is defined and erronous try to replace it with the right one
      if (rhs.tpe.isError)
        inferredTypeEdits(
          Some(
            AdjustTypeOpts(
              removeType(lastTokenPos, tpt.pos.end - 1),
              tpt.pos.toLsp.getEnd()
            )
          )
        )
      else {
        val correctedTypeNameEdit =
          new TextEdit(
            tpt.pos.withStart(lastTokenPos).toLsp,
            ": " + prettyType(rhs.tpe)
          )
        correctedTypeNameEdit :: additionalImports
      }
    }

    typedTree match {
      /* `val a = 1` or `var b = 2`
       *     turns into
       * `val a: Int = 1` or `var b: Int = 2`
       */
      case vl @ ValDef(_, name, tpt, rhs) if !vl.symbol.isParameter =>
        val nameEnd = findNameEnd(tpt.pos.start, name)
        val nameEndPos = tpt.pos.withEnd(nameEnd).withStart(nameEnd).toLsp
        adjustOpt.foreach(adjust => nameEndPos.setEnd(adjust.adjustedEndPos))
        lazy val typeNameEdit =
          new TextEdit(nameEndPos, ": " + prettyType(tpt.tpe))
        // if type is defined and erronous try to replace it with the right one
        if (tpt.pos.isRange && retryType)
          adjustType(rhs, tpt, vl.namePos.end)
        else typeNameEdit :: additionalImports

      /* `.map(a => a + a)`
       *     turns into
       * `.map((a: Int) => a + a)`
       */
      case vl @ ValDef(_, name, tpt, _) if vl.symbol.isParameter =>
        val nameEnd = findNameEnd(vl.pos.start, name)
        val namePos = tpt.pos.withEnd(nameEnd).withStart(nameEnd).toLsp

        def leftParenStart = vl.pos.withEnd(vl.pos.start).toLsp
        def leftParenEdit = new TextEdit(leftParenStart, "(")

        def needsParens = lastVisitedParentTrees match {
          /* Find how the function starts either with:
           * - `(` - then we need braces
           * - `{` - then we don't need braces
           */
          case _ :: (f: Function) :: (appl: Apply) :: _ =>
            val alreadyExistingBrace = params.text()(f.pos.start) == '('
            def needsNewBraces = params
              .text()
              .substring(appl.pos.start, vl.pos.start)
              .tokenize
              .toOption
              .exists {
                _.tokens.reverseIterator
                  .find(t => t.is[T.LeftParen] || t.is[T.LeftBrace])
                  .exists(_.is[T.LeftParen])
              }
            !alreadyExistingBrace && needsNewBraces
          case _ => false
        }

        val typeNameEdit = {
          val rightParen = if (needsParens) ")" else ""
          new TextEdit(namePos, ": " + prettyType(tpt.tpe) + rightParen)
        }

        if (needsParens) {
          leftParenEdit :: typeNameEdit :: additionalImports
        } else {
          typeNameEdit :: additionalImports
        }

      /* `def a[T](param : Int) = param`
       *     turns into
       * `def a[T](param : Int): Int = param`
       */
      case df @ DefDef(_, name, _, _, tpt, rhs) =>
        val nameEnd = findNameEnd(df.namePos.start, name)

        // search for `)` or `]` or defaut to name's end to insert type
        val lastParamOffset =
          if (tpt.pos.isRange) tpt.pos.start else rhs.pos.start
        val searchString = params
          .text()
          .substring(nameEnd, lastParamOffset) // cotains the parameters and =
        val lastTokenPos = searchString.tokenize.toOption
          .flatMap { tokens =>
            tokens.tokens.reverseIterator
              .find(t => t.is[T.RightParen] || t.is[T.RightBracket])
              .map(t => nameEnd + t.pos.end)
          }
          .getOrElse(nameEnd)

        val insertPos =
          rhs.pos.withStart(lastTokenPos).withEnd(lastTokenPos).toLsp
        adjustOpt.foreach(adjust => insertPos.setEnd(adjust.adjustedEndPos))
        val typeNameEdit =
          new TextEdit(insertPos, ": " + prettyType(tpt.tpe))
        if (tpt.pos.isRange && retryType) {
          // if type is defined and erronous try to replace it with the right one
          adjustType(rhs, tpt, lastTokenPos)
        } else
          typeNameEdit :: additionalImports

      /* `case t =>`
       *  turns into
       * `case t: Int =>`
       */
      case bind @ Bind(name, body) =>
        def openingParenPos = body.pos.withEnd(body.pos.start)
        def openingParen = new TextEdit(openingParenPos.toLsp, "(")

        val insertStart = findNameEnd(bind.pos.start, name)
        val insertPos = bind.pos.withEnd(insertStart).withStart(insertStart)

        /* In case it's an infix pattern match
         * we need to add () for example in:
         * case (head : Int) :: tail =>
         */
        val needsParens = lastVisitedParentTrees match {
          case _ :: Apply(_: Ident, args) :: _ if args.size > 1 =>
            val firstEnd = args(0).pos.end
            val secondStart = args(1).pos.start
            val hasDot = params
              .text()
              .substring(firstEnd, secondStart)
              .tokenize
              .toOption
              .exists(_.tokens.exists(_.is[T.Comma]))
            !hasDot
          case _ => false
        }

        val typeNameEdit = {
          val rightParen = if (needsParens) ")" else ""
          new TextEdit(
            insertPos.toLsp,
            ": " + prettyType(body.tpe) + rightParen
          )
        }

        if (needsParens) {
          openingParen :: typeNameEdit :: additionalImports
        } else {
          typeNameEdit :: additionalImports
        }
      case _ =>
        Nil
    }
  }
}
