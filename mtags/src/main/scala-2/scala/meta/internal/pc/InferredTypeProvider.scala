package scala.meta.internal.pc

import scala.meta._
import scala.meta.pc.OffsetParams
import scala.meta.tokens.{Token => T}

import org.eclipse.lsp4j.TextEdit

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

  def inferredTypeEdits(): List[TextEdit] = {

    val unit = addCompilationUnit(
      code = params.text(),
      filename = params.uri().toString(),
      cursor = None
    )

    val pos = unit.position(params.offset)
    val typedTree = typedTreeAt(pos)
    val importPosition = autoImportPosition(pos, params.text())
    val context = doLocateImportContext(pos, importPosition)
    val history = new ShortenedNames(
      lookupSymbol = name => context.lookupSymbol(name, _ => true) :: Nil
    )

    def additionalImports = importPosition match {
      case None =>
        // No import position means we can't insert an import without clashing with
        // existing symbols in scope, so we just do nothing
        Nil
      case Some(importPosition) =>
        history.autoImports(
          pos,
          context,
          importPosition.offset,
          importPosition.indent,
          importPosition.padTop
        )
    }

    def prettyType(tpe: Type) =
      metalsToLongString(tpe.widen.finalResultType, history)

    typedTree match {
      /* `val a = 1` or `var b = 2`
       *     turns into
       * `val a: Int = 1` or `var b: Int = 2`
       */
      case vl @ ValDef(_, name, tpt, _) if !vl.symbol.isParameter =>
        // dropLocal will remove a space that might appear at the of a name in some places
        val nameEnd = tpt.pos.start + name.dropLocal.length()
        val nameEndPos = tpt.pos.withEnd(nameEnd).withStart(nameEnd).toLSP
        val typeNameEdit = new TextEdit(nameEndPos, ": " + prettyType(tpt.tpe))
        typeNameEdit :: additionalImports

      /* `.map(a => a + a)`
       *     turns into
       * `.map((a: Int) => a + a)`
       */
      case vl @ ValDef(_, name, tpt, _) if vl.symbol.isParameter =>
        val nameEnd = vl.pos.start + name.length()
        val namePos = tpt.pos.withEnd(nameEnd).withStart(nameEnd).toLSP

        def leftParenStart = vl.pos.withEnd(vl.pos.start).toLSP
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
      case DefDef(_, name, _, _, tpt, rhs) =>
        val nameEnd = tpt.pos.start + name.length()

        // search for `)` or `]` or defaut to name's end to insert type
        val searchString = params
          .text()
          .substring(nameEnd, rhs.pos.start) // cotains the parameters and =
        val lastTokenPos = searchString.tokenize.toOption
          .flatMap(tokens =>
            tokens.tokens.reverseIterator
              .find(t => t.is[T.RightParen] || t.is[T.RightBracket])
              .map(t => nameEnd + t.pos.end)
          )
          .getOrElse(nameEnd)

        val insertPos = rhs.pos.withStart(lastTokenPos).withEnd(lastTokenPos)
        val typeNameEdit =
          new TextEdit(insertPos.toLSP, ": " + prettyType(tpt.tpe))

        typeNameEdit :: additionalImports

      /* `case t =>`
       *  turns into
       * `case t: Int =>`
       */
      case bind @ Bind(name, body) =>
        def openingParenPos = body.pos.withEnd(body.pos.start)
        def openingParen = new TextEdit(openingParenPos.toLSP, "(")

        val insertStart = bind.pos.start + name.length()
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
            insertPos.toLSP,
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
