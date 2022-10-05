package scala.meta.internal.pc
package completions

import java.nio.file.Path

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
    workspace: Option[Path],
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
          CompletionPos.infer(pos, params, path)(using newctx)
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
            workspace,
          ).completions()
        val autoImportsGen = AutoImports.generator(
          completionPos.sourcePos,
          params.text,
          unit.tpdTree,
          indexedCtx,
          config,
        )

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
    // for s" $@@ " or s" ${@@ "
    def isDollar = offset > 2 && (text.charAt(offset - 1) == '$' ||
      text.charAt(offset - 1) == '{' && text.charAt(offset - 2) == '$')

    if isStartMultilineComment then
      // Insert potentially missing `*/` to avoid comment out all codes after the "/**".
      text.substring(0, offset) + "*/" + text.substring(offset)
    else if isEmptyLine(offset, offset) || isDollar then
      text.substring(0, offset) + Cursor.value + text.substring(
        offset
      )
    else text
  end applyCompletionCursor

  private def completionItems(
      completion: CompletionValue,
      // history: ShortenedNames,
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
    val editRange = completionPos.toEditRange

    // For overloaded signatures we get multiple symbols, so we need
    // to recalculate the description
    // related issue https://github.com/lampepfl/dotty/issues/11941
    lazy val kind: CompletionItemKind = completion.completionItemKind
    val description = completion.description(printer)
    val label = completion.labelWithDescription(printer)
    val ident = completion.insertText.getOrElse(completion.label)

    def mkItem0(
        label: String,
        nameEdit: TextEdit,
        additionalEdits: List[TextEdit] = Nil,
        filterText: Option[String] = None,
        command: Option[String] = None,
    ): CompletionItem =
      val item = new CompletionItem(label)
      item.setSortText(f"${idx}%05d")
      item.setDetail(description)
      item.setFilterText(filterText.getOrElse(completion.label))
      item.setTextEdit(nameEdit)
      item.setAdditionalTextEdits(additionalEdits.asJava)

      completion match
        case v: CompletionValue.Symbolic =>
          val completionItemDataKind = v match
            case _: CompletionValue.Override =>
              CompletionItemData.OverrideKind
            case _ => null
          item.setData(
            CompletionItemData(
              SemanticdbSymbols.symbolName(v.symbol),
              buildTargetIdentifier,
              kind = completionItemDataKind,
            ).toJson
          )
        case _: CompletionValue.Document =>
          item.setInsertTextMode(InsertTextMode.AsIs)
        case _ =>
      end match

      item.setTags(completion.lspTags.asJava)

      if config.isCompletionSnippetsEnabled then
        item.setInsertTextFormat(InsertTextFormat.Snippet)

      command.foreach { command =>
        item.setCommand(new Command("", command))
      }

      item.setKind(kind)
      item
    end mkItem0

    def mkItem(
        label: String,
        value: String,
        additionalEdits: List[TextEdit] = Nil,
        filterText: Option[String] = None,
        range: Option[LspRange] = None,
        command: Option[String] = None,
    ): CompletionItem =
      val nameEdit = new TextEdit(
        range.getOrElse(editRange),
        value,
      )
      mkItem0(
        label,
        nameEdit,
        additionalEdits,
        filterText,
        command,
      )
    end mkItem

    def mkWorkspaceItem(
        value: String,
        additionalEdits: List[TextEdit] = Nil,
    ): CompletionItem =
      mkItem(label, value, additionalEdits)

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

    def mkItemWithImports(
        v: CompletionValue.Workspace | CompletionValue.Extension |
          CompletionValue.Interpolator
    ) =
      val sym = v.symbol
      val suffix = v.snippetSuffix
      path match
        case (_: Ident) :: (_: Import) :: _ =>
          mkWorkspaceItem(sym.fullNameBackticked)
        case _ =>
          autoImports.editsForSymbol(sym) match
            case Some(edits) =>
              edits match
                case AutoImportEdits(Some(nameEdit), other) =>
                  mkItem0(
                    label,
                    nameEdit,
                    v.additionalEdits ++ other.toList,
                    filterText = v.filterText,
                  )
                case _ =>
                  mkItem(
                    label,
                    v.insertText.getOrElse(
                      ident.backticked + completionTextSuffix
                    ),
                    v.additionalEdits ++ edits.edits,
                    range = v.range,
                    filterText = v.filterText,
                  )
            case None =>
              val r = indexedContext.lookupSym(sym)
              r match
                case IndexedContext.Result.InScope =>
                  mkItem(
                    label,
                    ident.backticked + completionTextSuffix,
                    additionalEdits = v.additionalEdits,
                  )
                case _ if isInStringInterpolation =>
                  mkWorkspaceItem(
                    "{" + sym.fullNameBackticked + completionTextSuffix + "}",
                    additionalEdits = v.additionalEdits,
                  )
                case _ =>
                  mkWorkspaceItem(
                    sym.fullNameBackticked + completionTextSuffix,
                    additionalEdits = v.additionalEdits,
                  )
              end match
      end match
    end mkItemWithImports

    completion match
      case v: (CompletionValue.Workspace | CompletionValue.Extension) =>
        mkItemWithImports(v)
      case v: CompletionValue.Interpolator if v.isWorkspace || v.isExtension =>
        mkItemWithImports(v)
      case CompletionValue.Override(
            _,
            value,
            _,
            shortNames,
            filterText,
            start,
          ) =>
        val additionalEdits =
          shortNames
            .sortBy(nme => nme.name)
            .flatMap(name => autoImports.forShortName(name))
            .flatten
        mkItem(
          label,
          value,
          additionalEdits,
          filterText,
          Some(completionPos.copy(start = start).toEditRange),
        )
      case CompletionValue.NamedArg(_, _) =>
        mkItem(
          label,
          label.replace("$", "$$"),
        ) // escape $ for snippet
      case CompletionValue.Keyword(_, text) =>
        mkItem(label, text.getOrElse(label))
      case CompletionValue.Document(_, doc, desc) =>
        mkItem(label, doc, filterText = Some(desc))
      case CompletionValue.Autofill(value) => mkItem(label, value)
      case CompletionValue.CaseKeyword(
            _,
            _,
            text,
            additionalEdit,
            range,
            command,
          ) =>
        mkItem(
          label,
          text.getOrElse(label),
          additionalEdits = additionalEdit,
          range = range,
          command = command,
        )
      case CompletionValue.MatchCompletion(
            _,
            text,
            additionalEdits,
            desc,
          ) =>
        mkItem(label, text.getOrElse(label), additionalEdits = additionalEdits)
      case _ =>
        val insert = completion.insertText.getOrElse(ident.backticked)
        mkItem(
          label,
          insert + completionTextSuffix,
          additionalEdits = completion.additionalEdits,
          range = completion.range,
          filterText = completion.filterText,
        )
    end match
  end completionItems
end CompletionProvider

case object Cursor:
  val value = "CURSOR"
