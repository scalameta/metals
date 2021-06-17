package scala.meta.internal.mtags

import java.net.URI

import scala.annotation.tailrec

import scala.meta.internal.pc.MetalsInteractive
import scala.meta.pc.OffsetParams

import dotty.tools.dotc.Driver
import dotty.tools.dotc.core.Contexts._
import dotty.tools.dotc.core.NameOps._
import dotty.tools.dotc.core.Names._
import dotty.tools.dotc.core.Symbols._
import dotty.tools.dotc.interactive.Interactive
import dotty.tools.dotc.interactive.InteractiveDriver
import dotty.tools.dotc.util.SourcePosition
import dotty.tools.dotc.util.Spans
import org.eclipse.{lsp4j => l}

object MtagsEnrichments extends CommonMtagsEnrichments {

  extension (driver: InteractiveDriver)
    def sourcePosition(params: OffsetParams): SourcePosition =
      sourcePosition(params.uri, params.offset)

    def sourcePosition(uri: URI, offset: Int): SourcePosition =
      val source = driver.openedFiles(uri)
      val p = Spans.Span(offset)
      new SourcePosition(source, p)

    def localContext(params: OffsetParams): Context = {
      if (driver.currentCtx.run.units.isEmpty)
        throw new RuntimeException(
          "No source files were passed to the Scala 3 presentation compiler"
        )
      val unit = driver.currentCtx.run.units.head
      val tree = unit.tpdTree
      val pos = driver.sourcePosition(params)
      val path =
        Interactive.pathTo(driver.openedTrees(params.uri), pos)(using
          driver.currentCtx
        )

      val newctx = driver.currentCtx.fresh.setCompilationUnit(unit)
      val tpdPath =
        Interactive.pathTo(newctx.compilationUnit.tpdTree, pos.span)(using
          newctx
        )
      MetalsInteractive.contextOfPath(tpdPath)(using newctx)
    }

  extension (pos: SourcePosition)
    def toLSP: l.Range = {
      new l.Range(
        new l.Position(pos.startLine, pos.startColumn),
        new l.Position(pos.endLine, pos.endColumn)
      )
    }

    def toLocation: Option[l.Location] = {
      for {
        uri <- InteractiveDriver.toUriOption(pos.source)
        range <- if (pos.exists) Some(pos.toLSP) else None
      } yield new l.Location(uri.toString, range)
    }

  extension (sym: Symbol)(using Context) {
    def fullNameBackticked: String = {
      @tailrec
      def loop(acc: List[String], sym: Symbol): List[String] = {
        if (sym == NoSymbol || sym.isRoot || sym.isEmptyPackage) acc
        else if (sym.isPackageObject) loop(acc, sym.owner)
        else {
          val v = KeywordWrapper.Scala3.backtickWrap(sym.decodedName)
          loop(v :: acc, sym.owner)
        }
      }
      loop(Nil, sym).mkString(".")
    }

    def decodedName: String = sym.name.decoded

    def nameBackticked: String =
      KeywordWrapper.Scala3.backtickWrap(sym.decodedName)
  }

  extension (name: Name)(using Context) {
    def decoded: String = name.stripModuleClassSuffix.show
  }

  extension (s: String) {
    def backticked: String =
      KeywordWrapper.Scala3.backtickWrap(s)
  }

}
