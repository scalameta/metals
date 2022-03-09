package scala.meta.internal.pc
package completions

import scala.annotation.tailrec
import scala.collection.JavaConverters.*

import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.internal.pc.AutoImports.AutoImportEdits
import scala.meta.internal.pc.AutoImports.AutoImportsGenerator
import scala.meta.internal.pc.printer.MetalsPrinter
import scala.meta.internal.tokenizers.Chars
import scala.meta.pc.OffsetParams
import scala.meta.pc.PresentationCompilerConfig
import scala.meta.pc.SymbolSearch

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.interactive.Interactive
import dotty.tools.dotc.interactive.InteractiveDriver
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.TextEdit

class CompletionsProvider(
    search: SymbolSearch,
    driver: InteractiveDriver,
    params: OffsetParams,
    config: PresentationCompilerConfig,
    buildTargetIdentifier: String
):

  def completions(): CompletionList =
    val uri = params.uri

    val code = applyCompletionCursor(params)
    val sourceFile = CompilerInterfaces.toSource(params.uri, code)
    driver.run(uri, sourceFile)

    val ctx = driver.currentCtx
    val pos = driver.sourcePosition(params)
    val (items, isIncomplete) = driver.compilationUnits.get(uri) match
      case Some(unit) =>
        val path =
          Interactive.pathTo(driver.openedTrees(uri), pos)(using ctx)

        val newctx = ctx.fresh.setCompilationUnit(unit)
        val tpdPath =
          Interactive.pathTo(newctx.compilationUnit.tpdTree, pos.span)(using
            newctx
          )
        val locatedCtx =
          MetalsInteractive.contextOfPath(tpdPath)(using newctx)
        val indexedCtx = IndexedContext(locatedCtx)
        val completionPos =
          CompletionPos.infer(pos, params.text, path)(using newctx)
        val (completions, searchResult) =
          new Completions(
            pos,
            ctx.fresh.setCompilationUnit(unit),
            search,
            buildTargetIdentifier,
            completionPos,
            indexedCtx,
            path
          ).completions()
        val autoImportsGen = AutoImports.generator(
          completionPos.sourcePos,
          params.text,
          unit.tpdTree,
          indexedCtx,
          config
        )

        val items = completions.zipWithIndex.map { case (item, idx) =>
          completionItems(
            item,
            idx,
            autoImportsGen,
            completionPos,
            path,
            indexedCtx
          )(using newctx)
        }
        val isIncomplete = searchResult match
          case SymbolSearch.Result.COMPLETE => false
          case SymbolSearch.Result.INCOMPLETE => true
        (items, isIncomplete)
      case None => (Nil, false)

    new CompletionList(
      isIncomplete,
      items.asJava
    )
  end completions

  /**
   * In case if completion comes from empty line like:
   * ```
   * class Foo:
   *   val a = 1
   *   @@
   * ```
   * it's required to modify actual code by addition Ident.
   *
   * Otherwise, completion poisition doesn't point at any tree
   * because scala parser trim end position to the last statement pos.
   */
  private def applyCompletionCursor(params: OffsetParams): String =
    import params.*

    @tailrec
    def isEmptyLine(idx: Int, initial: Int): Boolean =
      if idx < 0 then true
      else if idx >= text.length then isEmptyLine(idx - 1, initial)
      else
        val ch = text.charAt(idx)
        val isNewline = ch == '\n'
        if Chars.isWhitespace(ch) || (isNewline && idx == initial) then
          isEmptyLine(idx - 1, initial)
        else if isNewline then true
        else false

    if isEmptyLine(offset, offset) then
      text.substring(0, offset) + "CURSOR" + text.substring(offset)
    else text
  end applyCompletionCursor

  private def completionItems(
      completion: CompletionValue,
      // history: ShortenedNames,
      idx: Int,
      autoImports: AutoImportsGenerator,
      completionPos: CompletionPos,
      path: List[Tree],
      indexedContext: IndexedContext
  )(using ctx: Context): CompletionItem =
    val printer = MetalsPrinter.standard(indexedContext)
    val editRange = completionPos.toEditRange

    // For overloaded signatures we get multiple symbols, so we need
    // to recalculate the description
    // related issue https://github.com/lampepfl/dotty/issues/11941
    lazy val kind: CompletionItemKind = completion.completionItemKind

    val description =
      completion.description(printer)

    def mkItem0(
        ident: String,
        nameEdit: TextEdit,
        isFromWorkspace: Boolean = false,
        additionalEdits: List[TextEdit] = Nil
    ): CompletionItem =

      val label =
        kind match
          case CompletionItemKind.Method =>
            s"${ident}${description}"
          case CompletionItemKind.Variable | CompletionItemKind.Field =>
            s"${ident}: ${description}"
          case CompletionItemKind.Module | CompletionItemKind.Class =>
            if isFromWorkspace then s"${ident} -${description}"
            else s"${ident}${description}"
          case _ =>
            ident
      val item = new CompletionItem(label)

      item.setSortText(f"${idx}%05d")
      item.setDetail(description)
      item.setFilterText(completion.label)

      item.setTextEdit(nameEdit)

      item.setAdditionalTextEdits(additionalEdits.asJava)

      completion.documentation
        .filter(_.nonEmpty)
        .foreach(doc => item.setDocumentation(doc.toMarkupContent))

      item.setTags(completion.lspTags.asJava)

      item.setKind(kind)
      item
    end mkItem0

    def mkItem(
        ident: String,
        value: String,
        isFromWorkspace: Boolean = false,
        additionalEdits: List[TextEdit] = Nil
    ): CompletionItem =
      val nameEdit = new TextEdit(
        editRange,
        value
      )
      mkItem0(ident, nameEdit, isFromWorkspace, additionalEdits)

    def mkWorkspaceItem(
        ident: String,
        value: String,
        additionalEdits: List[TextEdit] = Nil
    ): CompletionItem =
      mkItem(ident, value, isFromWorkspace = true, additionalEdits)

    val ident = completion.label
    completion match
      case CompletionValue.Workspace(label, sym) =>
        path match
          case (_: Ident) :: (_: Import) :: _ =>
            mkWorkspaceItem(
              ident,
              sym.fullNameBackticked
            )
          case _ =>
            autoImports.editsForSymbol(sym) match
              case Some(edits) =>
                edits match
                  case AutoImportEdits(Some(nameEdit), other) =>
                    mkItem0(
                      ident,
                      nameEdit,
                      isFromWorkspace = true,
                      other.toList
                    )
                  case _ =>
                    mkItem(
                      ident,
                      ident.backticked,
                      isFromWorkspace = true,
                      edits.edits
                    )
              case None =>
                val r = indexedContext.lookupSym(sym)
                r match
                  case IndexedContext.Result.InScope =>
                    mkItem(ident, ident.backticked)
                  case _ => mkWorkspaceItem(ident, sym.fullNameBackticked)
      case CompletionValue.NamedArg(label, _) =>
        mkItem(ident, ident.replace("$", "$$")) // escape $ for snippet
      case CompletionValue.Keyword(label, text) => mkItem(label, text)
      case _ => mkItem(ident, ident.backticked)
    end match
  end completionItems
end CompletionsProvider
