package scala.meta.internal.pc
package completions

import java.{util as ju}

import scala.collection.JavaConverters.*

import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.internal.pc.AutoImports.AutoImport
import scala.meta.internal.pc.AutoImports.AutoImportsGenerator
import scala.meta.internal.pc.printer.MetalsPrinter
import scala.meta.pc.OffsetParams
import scala.meta.pc.PresentationCompilerConfig
import scala.meta.pc.PresentationCompilerConfig.OverrideDefFormat
import scala.meta.pc.SymbolSearch

import dotty.tools.dotc.ast.tpd.Tree
import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Definitions
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.Flags.*
import dotty.tools.dotc.core.NameKinds.DefaultGetterName
import dotty.tools.dotc.core.NameKinds.NameKind
import dotty.tools.dotc.core.Names.Name
import dotty.tools.dotc.core.StdNames
import dotty.tools.dotc.core.SymDenotations.SymDenotation
import dotty.tools.dotc.core.Symbols.NoSymbol
import dotty.tools.dotc.core.Symbols.Symbol
import dotty.tools.dotc.core.Types.Type
import dotty.tools.dotc.interactive.Interactive
import dotty.tools.dotc.interactive.InteractiveDriver
import dotty.tools.dotc.util.SourceFile
import dotty.tools.dotc.util.SourcePosition
import dotty.tools.dotc.util.Spans.Span
import org.eclipse.{lsp4j as l}
import scala.collection.mutable.ListBuffer
import scala.collection.mutable

object CaseKeywordCompletion:
  private type TargetDef = TypeDef | DefDef

  private def defaultIndent(tabIndent: Boolean) =
    if tabIndent then 1 else 2

  /**
   * @param td A surrounded type definition being complete
   * @param filterName A prefix string for filtering, if None no filter
   * @param start The starting point of the completion. For example, starting point is `*`
   *              `*override def f|` (where `|` represents the cursor position).
   */
  def contribute(
      selector: Tree,
      completionPos: CompletionPos,
      indexedContext: IndexedContext,
  ): List[CompletionValue] =
    import indexedContext.ctx
    val parents = selector.tpe.typeSymbol
    val result = ListBuffer.empty[Symbol]




  end contribute
end CaseKeywordCompletion
