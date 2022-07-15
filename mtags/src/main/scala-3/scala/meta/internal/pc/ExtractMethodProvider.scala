package scala.meta.internal.pc

import java.net.URI
import java.nio.file.Paths

import scala.annotation.tailrec
import scala.meta as m
import org.eclipse.{lsp4j => l}

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
) {

  def extractMethod(): List[TextEdit] = {
    val uri = params.uri
    val filePath = Paths.get(uri)
    val source = SourceFile.virtual(filePath.toString, params.text)
    driver.run(uri, source)
    val unit = driver.currentCtx.run.units.head
    val pos = driver.sourcePosition(params)
    pprint.pprintln(pos)

    val path =
      Interactive.pathTo(driver.openedTrees(uri), pos)(using driver.currentCtx)

    given locatedCtx: Context = driver.localContext(params)
    val indexedCtx = IndexedContext(locatedCtx)

    // pprint.pprintln("Wszedlem do providera")

    // // def isBlockOrTemplate(t: tpd.Tree) = {
    // //   t match {
    // //     case temp : Template[_] => true
    // //     case block: Block[_] => true
    // //     case _ => false
    // //   }
    // // }
      
    // // def defs(t: tpd.Tree): Seq[tpd.Tree] = {
    // //   t match {
    // //     case template: Template[_] =>
    // //       template.body.filter(_.isDef)
    // //     case block: Block[_] => 
    // //       block.stats.filter(_.isDef)
    // //   }
    // // }
    val printer = MetalsPrinter.standard(
          indexedCtx,
          symbolSearch,
          includeDefaultParam = MetalsPrinter.IncludeDefaultParam.ResolveLater,
        )
    def localVariables(ts: Seq[tpd.Tree]): Seq[(TermName, String)] = {
      ts.flatMap(
        _ match
          case vl @ ValDef(sym, tpt, _) => Seq((sym, printer.tpe(tpt.tpe)))
          // case dl @ DefDef(name, _, tpt, rhs) => 
          //   val paramss = localVariablesInDefDef(dl)
          //   val paramTypes = paramss.map(_.map(_._2)).map(ls => s"(${ls.mkString(", ")})").mkString
          //  Seq((name, paramTypes + " => " + printer.tpe(tpt.tpe))) ++ paramss.flatten
          case b: Block[_] => localVariables(b.stats)
          case t: Template[_] => /*localVariablesInDefDef(t.constr).flatten ++ */ localVariables(t.body)
          case _ => Nil
      )
    }

    // def localVariablesInDefDef[T](d: DefDef[_]): List[List[(TermName, String)]] = {
    //   val inparams = d.paramss.map(_.flatMap( p =>
    //     p match {
    //       case vl @ ValDef(sym, tpt, _) => Some((sym, printer.tpe(tpt.tpe)))
    //       case _ => None
    //     }
    //   ))
    //   inparams
    // }
    

    def namesInVal(t: tpd.Tree): Set[TermName] = {
      t match
        case Apply(fun, args) => namesInVal(fun) ++ args.flatMap(namesInVal(_)).toSet
        case TypeApply(fun, args) => namesInVal(fun) ++ args.flatMap(namesInVal(_)).toSet
        case Select(qualifier, name) => namesInVal(qualifier) + name.toTermName
        case Ident(name) => Set(name.toTermName)
        case _ => Set()
    }
    
    val newValue = path.headOption
    pprint.pprintln(newValue)
    val namesInAppl = newValue.map(namesInVal(_))
    val locals = localVariables(path).reverse.toMap
    pprint.pprintln(namesInAppl)
    pprint.pprintln(locals)
    val withType = namesInAppl.map(s => locals.filter((key, tp) => s.contains(key)))
    val typs = withType.map(ts => ts.map((k,v) => s"$k: $v").mkString(", ")).getOrElse("nonetype") match {
      case "" => "empty"
      case o => o
    }
    pprint.pprintln(typs)

    List(
      new l.TextEdit(
        pos.toLSP, typs
      )
    )
  }

  
}