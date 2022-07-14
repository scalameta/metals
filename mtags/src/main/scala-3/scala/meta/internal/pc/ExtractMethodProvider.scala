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
) {

  def extractMethod(): List[TextEdit] = {
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
    
    // def localVariables(ts: Seq[tpd.Tree]): Seq[Seq[(TermName, String)]] = {
    //   ts.map(
    //     _ match
    //       case d: ValDef[_] => Seq((d.name, d.tpt.tpe.toString()))
    //       case d: DefDef[_] => localVariablesInDefDef(d)
    //       case b: Block[_] => localVariables(b.stats).flatten
    //       case t: Template[_] => localVariablesInDefDef(t.constr) ++ localVariables(t.body).flatten
    //       case _ => Nil
    //   )
    // }

    // def localVariablesInDefDef[T](d: DefDef[_]): List[(TermName, String)] = {
    //   val inparams = d.paramss.flatten.flatMap( p =>
    //     p match {
    //       case v: ValDef[_] => Some(v.name, v.tpt.tpe.toString())
    //       case _ => None
    //     }
    //   )
    //   (d.name, d.tpt.toString()) :: inparams
    // }


    // val newValue = path.headOption
    // pprint.pprintln(newValue)
    // val locals = localVariables(path)
    // pprint.pprintln(path.zip(locals))
    Nil
  }
}