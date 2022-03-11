package scala.meta.internal.pc

import java.net.URI
import java.nio.file.Paths

import scala.annotation.tailrec
import scala.meta as m

import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.internal.pc.AutoImports.AutoImportsGenerator
import scala.meta.internal.pc.printer.MetalsPrinter
import scala.meta.internal.pc.printer.ShortenedNames
import scala.meta.pc.OffsetParams
import scala.meta.pc.PresentationCompilerConfig
import scala.meta.tokens.{Token as T}

import dotty.tools.dotc.ast.Trees.*
import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.interactive.Interactive
import dotty.tools.dotc.interactive.InteractiveDriver
import dotty.tools.dotc.util.SourceFile
import dotty.tools.dotc.util.SourcePosition
import dotty.tools.dotc.util.Spans
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
 * @param params position and actual source
 * @param driver Scala 3 interactive compiler driver
 * @param config presentation compielr configuration
 */
final class InferredTypeProvider(
    params: OffsetParams,
    driver: InteractiveDriver,
    config: PresentationCompilerConfig
):

  def inferredTypeEdits(): List[TextEdit] =
    val uri = params.uri
    val filePath = Paths.get(uri)
    val source = SourceFile.virtual(filePath.toString, params.text)
    driver.run(uri, source)
    val unit = driver.currentCtx.run.units.head
    val pos = driver.sourcePosition(params)
    val path =
      Interactive.pathTo(driver.openedTrees(uri), pos)(using driver.currentCtx)

    given locatedCtx: Context = driver.localContext(params)
    val indexedCtx = IndexedContext(locatedCtx)
    val autoImportsGen = AutoImports.generator(
      pos,
      params.text,
      unit.tpdTree,
      indexedCtx,
      config
    )
    val shortenedNames = new ShortenedNames(indexedCtx)

    def imports: List[TextEdit] =
      shortenedNames.imports(autoImportsGen)

    def printType(tpe: Type): String =
      val printer = MetalsPrinter.forInferredType(shortenedNames, indexedCtx)
      printer.tpe(tpe)

    /*
     * Get the exact position in ValDef pattern for val (a, b) = (1, 2)
     * val ((a, c), b) = ((1, 3), 2) will be covered by Bind
     * This is needed to Scala versions pre 3.1.2.
     */
    def editForTupleUnapply(
        applied: AppliedType,
        metaPattern: m.Pat,
        valdefOffset: Int
    )(using ctx: Context) =
      import scala.meta.*
      metaPattern match
        case tpl: m.Pat.Tuple =>
          val newOffset = params.offset - valdefOffset + 4
          val tupleIndex = tpl.args.indexWhere { p =>
            p.pos.start <= newOffset && p.pos.end >= newOffset
          }
          if tupleIndex >= 0 then
            val tuplePartTpe = applied.args(tupleIndex)
            val typeEndPos = tpl.args(tupleIndex).pos.end
            val namePos = typeEndPos + valdefOffset - 4
            val lspPos = new SourcePosition(source, Spans.Span(namePos)).toLSP
            val typeNameEdit =
              new TextEdit(
                lspPos,
                ": " + printType(tuplePartTpe)
              )
            typeNameEdit :: imports
          else Nil
        case _ => Nil
      end match
    end editForTupleUnapply

    path.headOption match
      /* `val a = 1` or `var b = 2`
       *     turns into
       * `val a: Int = 1` or `var b: Int = 2`
       *
       *`.map(a => a + a)`
       *     turns into
       * `.map((a: Int) => a + a)`
       */
      case Some(vl @ ValDef(sym, tpt, _)) =>
        val isParam = path.tail.headOption.exists(_.symbol.isAnonymousFunction)
        def baseEdit(withParens: Boolean) =
          new TextEdit(
            vl.namePos.endPos.toLSP,
            ": " + printType(tpt.tpe) + {
              if withParens then ")" else ""
            }
          )

        def checkForParensAndEdit(
            applyEndingPos: Int,
            toCheckFor: Char,
            blockStartPos: SourcePosition
        ) =
          val text = params.text.toString()
          val isParensFunction: Boolean = text(applyEndingPos) == toCheckFor

          val alreadyHasParens =
            text(blockStartPos.start) == '('

          if isParensFunction && !alreadyHasParens then
            new TextEdit(blockStartPos.toLSP, "(") :: baseEdit(withParens =
              true
            ) :: Nil
          else baseEdit(withParens = false) :: Nil
        end checkForParensAndEdit

        def typeNameEdit: List[TextEdit] =
          path match
            // lambda `map(a => ???)` apply
            case _ :: _ :: (block: untpd.Block) :: (appl: untpd.Apply) :: _
                if isParam =>
              checkForParensAndEdit(appl.fun.endPos.end, '(', block.startPos)

            // labda `map{a => ???}` apply
            // Ensures that this becomes {(a: Int) => ???} since parentheses
            // are required around the parameter of a lambda in Scala 3
            case valDef :: defDef :: (block: untpd.Block) :: (_: untpd.Block) :: (appl: untpd.Apply) :: _
                if isParam =>
              checkForParensAndEdit(appl.fun.endPos.end, '{', block.startPos)

            case _ =>
              baseEdit(withParens = false) :: Nil

        def simpleType =
          typeNameEdit ::: imports

        def tupleType(applied: AppliedType) =
          import scala.meta.*
          val pattern =
            params.text.substring(vl.startPos.start, tpt.startPos.start)

          dialects.Scala3("val " + pattern + "???").parse[Source] match
            case Parsed.Success(Source(List(valDef: m.Defn.Val))) =>
              editForTupleUnapply(
                applied,
                valDef.pats.head,
                vl.startPos.start
              )
            case _ => simpleType
        tpt.tpe match
          case applied: AppliedType =>
            tupleType(applied)
          case _ =>
            simpleType

      /* `def a[T](param : Int) = param`
       *     turns into
       * `def a[T](param : Int): Int = param`
       */
      case Some(DefDef(name, _, tpt, rhs)) =>
        val typeNameEdit =
          new TextEdit(
            tpt.endPos.toLSP,
            ": " + printType(tpt.tpe)
          )
        typeNameEdit :: imports

      /* `case t =>`
       *  turns into
       * `case t: Int =>`
       */
      case Some(bind @ Bind(name, body)) =>
        def baseEdit(withParens: Boolean) =
          new TextEdit(
            bind.endPos.toLSP,
            ": " + printType(body.tpe) + {
              if withParens then ")" else ""
            }
          )
        val typeNameEdit = path match
          /* In case it's an infix pattern match
           * we need to add () for example in:
           * case (head : Int) :: tail =>
           */
          case _ :: (unappl @ UnApply(_, _, patterns)) :: _
              if patterns.size > 1 =>
            import scala.meta.*
            val firstEnd = patterns(0).endPos.end
            val secondStart = patterns(1).startPos.start
            val hasDot = params
              .text()
              .substring(firstEnd, secondStart)
              .tokenize
              .toOption
              .exists(_.tokens.exists(_.is[T.Comma]))
            if !hasDot then
              val leftParen = new TextEdit(body.startPos.toLSP, "(")
              leftParen :: baseEdit(withParens = true) :: Nil
            else baseEdit(withParens = false) :: Nil

          case _ =>
            baseEdit(withParens = false) :: Nil
        typeNameEdit ::: imports

      /* `for(t <- 0 to 10)`
       *  turns into
       * `for(t: Int <- 0 to 10)`
       */
      case Some(i @ Ident(name)) =>
        val typeNameEdit = new TextEdit(
          i.endPos.toLSP,
          ": " + printType(i.tpe.widen)
        )
        typeNameEdit :: imports

      case _ =>
        Nil
    end match
  end inferredTypeEdits

end InferredTypeProvider
