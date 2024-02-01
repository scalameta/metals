package scala.meta.internal.pc
package completions

import java.nio.file.Path

import scala.collection.JavaConverters.*

import scala.meta.internal.metals.ReportContext
import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.internal.pc.AutoImports.AutoImportEdits
import scala.meta.internal.pc.AutoImports.AutoImportsGenerator
import scala.meta.internal.pc.printer.MetalsPrinter
import scala.meta.pc.OffsetParams
import scala.meta.pc.PresentationCompilerConfig
import scala.meta.pc.SymbolSearch

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.StdNames
import dotty.tools.dotc.interactive.Interactive
import dotty.tools.dotc.interactive.InteractiveDriver
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.InsertTextFormat
import org.eclipse.lsp4j.InsertTextMode
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.Range as LspRange

class CompletionProvider(
    search: SymbolSearch,
    driver: InteractiveDriver,
    params: OffsetParams,
    config: PresentationCompilerConfig,
    buildTargetIdentifier: String,
    folderPath: Option[Path],
)(using reports: ReportContext):
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
          Interactive.contextOfPath(tpdPath)(using newctx)
        val indexedCtx = IndexedContext(locatedCtx)
        val completionPos =
          CompletionPos.infer(pos, params, path)(using newctx)
        val autoImportsGen = AutoImports.generator(
          completionPos.sourcePos,
          params.text,
          unit.tpdTree,
          indexedCtx,
          config,
        )
        val (completions, searchResult) =
          new Completions(
            pos,
            params.text,
            ctx.fresh.setCompilationUnit(unit),
            search,
            buildTargetIdentifier,
            completionPos,
            indexedCtx,
            path,
            config,
            folderPath,
            autoImportsGen,
            driver.settings,
          ).completions()

        val items = completions.zipWithIndex.map { case (item, idx) =>
          completionItems(
            item,
            idx,
            autoImportsGen,
            completionPos,
            path,
            indexedCtx,
          )(using newctx)
        }
        val isIncomplete = searchResult match
          case SymbolSearch.Result.COMPLETE => false
          case SymbolSearch.Result.INCOMPLETE => true
        (items, isIncomplete)
      case None => (Nil, false)

    new CompletionList(
      isIncomplete,
      items.asJava,
    )
  end completions

  /**
   * In case if completion comes from empty line like:
   * {{{
   * class Foo:
   *   val a = 1
   *   @@
   * }}}
   * it's required to modify actual code by addition Ident.
   *
   * Otherwise, completion poisition doesn't point at any tree
   * because scala parser trim end position to the last statement pos.
   */
  private def applyCompletionCursor(params: OffsetParams): String =
    import params.*
    val isStartMultilineComment =
      val i = params.offset()
      i >= 3 && (params.text().charAt(i - 1) match
        case '*' =>
          params.text().charAt(i - 2) == '*' &&
          params.text().charAt(i - 3) == '/'
        case _ => false
      )
    if isStartMultilineComment then
      // Insert potentially missing `*/` to avoid comment out all codes after the "/**".
      text.substring(0, offset) + Cursor.value + "*/" + text.substring(offset)
    else
      text.substring(0, offset) + Cursor.value + text.substring(
        offset
      )
  end applyCompletionCursor

  private def completionItems(
      completion: CompletionValue,
      idx: Int,
      autoImports: AutoImportsGenerator,
      completionPos: CompletionPos,
      path: List[Tree],
      indexedContext: IndexedContext,
  )(using ctx: Context): CompletionItem =
    val printer = MetalsPrinter.standard(
      indexedContext,
      search,
      includeDefaultParam = MetalsPrinter.IncludeDefaultParam.ResolveLater,
    )

    // For overloaded signatures we get multiple symbols, so we need
    // to recalculate the description
    // related issue https://github.com/lampepfl/dotty/issues/11941
    lazy val kind: CompletionItemKind = completion.completionItemKind
    val description = completion.description(printer)
    val label = completion.labelWithDescription(printer)
    val ident = completion.insertText.getOrElse(completion.label)

    def mkItem(
        newText: String,
        additionalEdits: List[TextEdit] = Nil,
        range: Option[LspRange] = None,
    ): CompletionItem =
      val oldText =
        params.text.substring(completionPos.start, completionPos.end)
      val editRange =
        if newText.startsWith(oldText) then completionPos.stripSuffixEditRange
        else completionPos.toEditRange

      val textEdit = new TextEdit(range.getOrElse(editRange), newText)

      val item = new CompletionItem(label)
      item.setSortText(f"${idx}%05d")
      item.setDetail(description)
      item.setFilterText(
        completion.filterText.getOrElse(completion.label)
      )
      item.setTextEdit(textEdit)
      item.setAdditionalTextEdits(
        (completion.additionalEdits ++ additionalEdits).asJava
      )
      completion.insertMode.foreach(item.setInsertTextMode)

      completion
        .completionData(buildTargetIdentifier)
        .foreach(data => item.setData(data.toJson))

      item.setTags(completion.lspTags.asJava)

      if config.isCompletionSnippetsEnabled then
        item.setInsertTextFormat(InsertTextFormat.Snippet)

      completion.command.foreach { command =>
        item.setCommand(new Command("", command))
      }

      item.setKind(kind)
      item
    end mkItem

    val completionTextSuffix = completion.snippetSuffix.toEdit

    lazy val isInStringInterpolation =
      path match
        // s"My name is $name"
        case (_: Ident) :: (_: SeqLiteral) :: (_: Typed) :: Apply(
              Select(Apply(Select(Select(_, name), _), _), _),
              _,
            ) :: _ =>
          name == StdNames.nme.StringContext
        // "My name is $name"
        case Literal(Constant(_: String)) :: _ =>
          true
        case _ =>
          false

    lazy val backtickSoftKeyword = path match
      case (_: Select) :: _ => false
      case _ => true

    def mkItemWithImports(
        v: CompletionValue.Workspace | CompletionValue.Extension |
          CompletionValue.Interpolator | CompletionValue.ImplicitClass
    ) =
      val sym = v.symbol
      path match
        case (_: Ident) :: (_: Import) :: _ =>
          mkItem(sym.fullNameBackticked(backtickSoftKeyword = false))
        case _ =>
          autoImports.editsForSymbol(v.importSymbol) match
            case Some(edits) =>
              edits match
                case AutoImportEdits(Some(nameEdit), other) =>
                  mkItem(
                    nameEdit.getNewText(),
                    other.toList,
                    range = Some(nameEdit.getRange()),
                  )
                case _ =>
                  mkItem(
                    v.insertText.getOrElse(
                      ident.backticked(
                        backtickSoftKeyword
                      ) + completionTextSuffix
                    ),
                    edits.edits,
                    range = v.range,
                  )
            case None =>
              val r = indexedContext.lookupSym(sym)
              r match
                case IndexedContext.Result.InScope =>
                  mkItem(
                    v.insertText.getOrElse(
                      ident.backticked(
                        backtickSoftKeyword
                      ) + completionTextSuffix
                    ),
                    range = v.range,
                  )
                case _ if isInStringInterpolation =>
                  mkItem(
                    "{" + sym.fullNameBackticked + completionTextSuffix + "}",
                    range = v.range,
                  )
                case _ if v.isExtensionMethod =>
                  mkItem(
                    ident.backticked(
                      backtickSoftKeyword
                    ) + completionTextSuffix,
                    range = v.range,
                  )
                case _ =>
                  mkItem(
                    sym.fullNameBackticked(
                      backtickSoftKeyword
                    ) + completionTextSuffix,
                    range = v.range,
                  )
              end match
          end match
      end match
    end mkItemWithImports

    completion match
      case v: (CompletionValue.Workspace | CompletionValue.Extension |
            CompletionValue.ImplicitClass) =>
        mkItemWithImports(v)
      case v: CompletionValue.Interpolator if v.isWorkspace || v.isExtension =>
        mkItemWithImports(v)
      case _ =>
        val insert =
          completion.insertText.getOrElse(ident.backticked(backtickSoftKeyword))
        mkItem(
          insert + completionTextSuffix,
          range = completion.range,
        )
    end match
  end completionItems
end CompletionProvider

case object Cursor:
  val value = "CURSOR"
