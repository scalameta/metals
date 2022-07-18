package scala.meta.internal.pc

import java.net.URI
import java.nio.file.Paths

import scala.annotation.tailrec
import scala.meta as m
import org.eclipse.{lsp4j as l}

import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.internal.pc.AutoImports.AutoImportsGenerator
import scala.meta.internal.pc.printer.MetalsPrinter
import scala.meta.internal.pc.printer.ShortenedNames
import scala.meta.pc.OffsetParams
import scala.meta.pc.SymbolSearch
import scala.meta.pc.PresentationCompilerConfig
import dotty.tools.dotc.ast.Trees.*
import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.interactive.Interactive
import dotty.tools.dotc.interactive.InteractiveDriver
import dotty.tools.dotc.util.SourceFile
import dotty.tools.dotc.util.SourcePosition
import org.eclipse.lsp4j.TextEdit
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.core.Names.TermName
import dotty.tools.dotc.core.Types.Type

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
final class ExtractMethodProvider(
    params: OffsetParams,
    driver: InteractiveDriver,
    config: PresentationCompilerConfig,
    symbolSearch: SymbolSearch,
):

  def extractMethod(): List[TextEdit] =
    // pprint.pprintln("Tu wszedlem")
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

    def isBlockOrTemplate(t: tpd.Tree) =
      t match
        case temp: Template[?] => true
        case block: Block[?] => true
        case _ => false

    def defs(t: tpd.Tree): Option[Seq[tpd.Tree]] =
      t match
        case template: Template[?] =>
          Some(template.body.filter(_.isDef))
        case block: Block[?] =>
          Some(block.stats.filter(_.isDef))
        case _ => None

    val printer = MetalsPrinter.standard(
      indexedCtx,
      symbolSearch,
      includeDefaultParam = MetalsPrinter.IncludeDefaultParam.ResolveLater,
    )
    def localVariables(ts: Seq[tpd.Tree]): Seq[(TermName, String)] =
      ts.flatMap(
        _ match
          case vl @ ValDef(sym, tpt, _) => Seq((sym, printer.tpe(tpt.tpe)))
          // case dl @ DefDef(name, _, tpt, rhs) =>
          //   val paramss = localVariablesInDefDef(dl)
          //   val paramTypes = paramss.map(_.map(_._2)).map(ls => s"(${ls.mkString(", ")})").mkString
          //  Seq((name, paramTypes + " => " + printer.tpe(tpt.tpe))) ++ paramss.flatten
          case b: Block[?] => localVariables(b.stats)
          case t: Template[
                ?
              ] => /*localVariablesInDefDef(t.constr).flatten ++ */
            localVariables(t.body)
          case _ => Nil
      )

    def namesInVal(t: tpd.Tree): Set[TermName] =
      t match
        case Apply(fun, args) =>
          namesInVal(fun) ++ args.flatMap(namesInVal(_)).toSet
        case TypeApply(fun, args) =>
          namesInVal(fun) ++ args.flatMap(namesInVal(_)).toSet
        case Select(qualifier, name) => namesInVal(qualifier) + name.toTermName
        case Ident(name) => Set(name.toTermName)
        case _ => Set()

    def getName(t: tpd.Tree): Option[TermName] =
      t match
        case Ident(name) => Some(name.toTermName)
        case _ => None
    
    def nameDef(newName: TermName, t: tpd.Tree): Boolean =
      t match
        case dl @ DefDef(name, _, _, _) if name.asTermName == newName => true
        case _ => false

    def extracted(t: tpd.Tree): Option[tpd.Tree] = {
      t match
        case d @ DefDef(name, _,_,rhs) => Some(d.rhs) // problem z typem
        case _ => None
    }


    pprint.pprintln(path.headOption)
    val edits = for
      ident <- path.headOption
      name <- getName(ident)
      stat <- path.find(isBlockOrTemplate(_))
      allDefs <- defs(stat)
      nameDef <- allDefs.find(nameDef(name, _))
      apply <- extracted(nameDef)
      _ = pprint.pprintln(apply)
    yield {
      val namesInAppl = namesInVal(apply)
      val locals = localVariables(path).reverse.toMap
      pprint.pprintln("in appls")
      pprint.pprintln(namesInAppl)
      
      pprint.pprintln("locals")
      pprint.pprintln(locals)
      val withType =
        locals.filter((key, tp) => namesInAppl.contains(key))
      val typs = withType
        .map((k, v) => s"$k: $v").toList.sorted.mkString(", ")
      val tps = typs
      val applParams = withType.keys.toList.sorted.mkString(", ")
       pprint.pprintln("Typy")
       pprint.pprintln(tps)
      val nameShift = name.toString.length() + 1
      val defSpan = nameDef.startPos.span.shift(nameShift + 4)
      val defPos = new SourcePosition(source, defSpan).toLSP
      val applSpan = pos.startPos.span.shift(nameShift)
      val applPos = new SourcePosition(source, applSpan).toLSP

      List(
            new l.TextEdit(
              defPos, typs
            ),
            new l.TextEdit(
              applPos, applParams
            )
          )
      
    }
    edits.getOrElse(Nil)
  end extractMethod
end ExtractMethodProvider
