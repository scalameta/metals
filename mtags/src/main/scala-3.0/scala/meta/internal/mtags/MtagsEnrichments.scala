package scala.meta.internal.mtags

import dotty.tools.dotc.core.Contexts._
import dotty.tools.dotc.core.Names._
import dotty.tools.dotc.core.NameOps._
import dotty.tools.dotc.core.Symbols._
import dotty.tools.dotc.util.SourcePosition
import org.eclipse.{lsp4j => l}

import scala.annotation.tailrec

object MtagsEnrichments
    extends CommonMtagsEnrichments
    with VersionSpecificEnrichments {

  extension (pos: SourcePosition)
    def toLSP: l.Range = {
      new l.Range(
        new l.Position(pos.startLine, pos.startColumn),
        new l.Position(pos.endLine, pos.endColumn)
      )
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

    def decodedName: String = sym.name.stripModuleClassSuffix.show

    def nameBackticked: String =
      KeywordWrapper.Scala3.backtickWrap(sym.decodedName)
  }

  extension (s: String) {
    def backticked: String =
      KeywordWrapper.Scala3.backtickWrap(s)
  }

}
