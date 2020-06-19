package scala.meta.internal.pc

import java.{util => ju}

import scala.collection.mutable

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.pc.OffsetParams
import scala.meta.pc.SymbolSearch

import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionItemTag
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.InsertTextFormat
import org.eclipse.{lsp4j => l}

class CompletionProvider(
    val compiler: MetalsGlobal,
    params: OffsetParams
) {
  import compiler._

  private def cursorName: String = {
    val i = params.offset() - 1
    params.text().charAt(i) match {
      case '$' =>
        // Don't use `_` to avoid tokenization error in string interpolator.
        "CURSOR"
      case '{' if params.text().charAt(i - 1) == '$' =>
        // Insert potentially missing `}` to avoid "unclosed literal" error in String interpolator..
        CURSOR + "}"
      case '*'
          if params.text().charAt(i - 1) == '*' &&
            params.text().charAt(i - 2) == '/' =>
        // Insert potentially missing `*/` to avoid comment out all codes after the "/**".
        CURSOR + "*/"
      case _ =>
        // Default _CURSOR_ instrumentation.
        CURSOR
    }
  }

  def completions(): CompletionList = {
    val unit = addCompilationUnit(
      code = params.text,
      filename = params.uri().toString(),
      cursor = Some(params.offset),
      cursorName = cursorName
    )

    val pos = unit.position(params.offset)
    val isSnippet = isSnippetEnabled(pos, params.text())

    val (i, completion, editRange, query) = safeCompletionsAt(pos)

    val start = inferIdentStart(pos, params.text())
    val end = inferIdentEnd(pos, params.text())
    val oldText = params.text().substring(start, end)
    val stripSuffix = pos.withStart(start).withEnd(end).toLSP

    def textEdit(newText: String) = {
      if (newText == oldText) new l.TextEdit(stripSuffix, newText)
      else new l.TextEdit(editRange, newText)
    }

    val history = new ShortenedNames()

    val sorted = i.results.sorted(memberOrdering(query, history, completion))
    lazy val importPosition = autoImportPosition(pos, params.text())
    lazy val context = doLocateImportContext(pos, importPosition)

    val items = sorted.iterator.zipWithIndex.map {
      case (member, idx) =>
        params.checkCanceled()
        val symbolName = member.symNameDropLocal.decoded
        val ident = Identifier.backtickWrap(symbolName)
        val detail = member match {
          case o: OverrideDefMember => o.detail
          case t: TextEditMember if t.detail.isDefined => t.detail.get
          case _ => detailString(member, history)
        }
        val label = member match {
          case _: NamedArgMember =>
            s"$ident = "
          case o: OverrideDefMember =>
            o.label
          case o: TextEditMember =>
            o.label.getOrElse(ident)
          case o: WorkspaceMember =>
            s"$ident - ${o.sym.owner.fullName}"
          case _ =>
            if (member.sym.isMethod || member.sym.isValue) {
              ident + detail
            } else {
              ident
            }
        }
        val item = new CompletionItem(label)
        if (metalsConfig.isCompletionItemDetailEnabled) {
          item.setDetail(detail)
        }
        val templateSuffix =
          if (!isSnippet || !clientSupportsSnippets) ""
          else if (
            completion.isNew &&
            member.sym.dealiased.requiresTemplateCurlyBraces
          ) " {}"
          else ""

        val typeSuffix =
          if (!isSnippet || !clientSupportsSnippets) ""
          else if (completion.isType && member.sym.hasTypeParams) "[$0]"
          else if (completion.isNew && member.sym.hasTypeParams) "[$0]"
          else ""
        val suffix = typeSuffix + templateSuffix

        member match {
          case i: TextEditMember =>
            item.setFilterText(i.filterText)
          case i: OverrideDefMember =>
            item.setFilterText(i.filterText)
          case _ =>
            // Explicitly set filter text because the label has method signature and
            // fully qualified name.
            item.setFilterText(symbolName)
        }

        if (clientSupportsSnippets) {
          item.setInsertTextFormat(InsertTextFormat.Snippet)
        } else {
          item.setInsertTextFormat(InsertTextFormat.PlainText)
        }

        member match {
          case i: TextEditMember =>
            item.setTextEdit(i.edit)
            if (i.additionalTextEdits.nonEmpty) {
              item.setAdditionalTextEdits(i.additionalTextEdits.asJava)
            }
          case i: OverrideDefMember =>
            item.setTextEdit(i.edit)
            item.setAdditionalTextEdits(i.autoImports.asJava)
          case w: WorkspaceMember =>
            importPosition match {
              case None =>
                // No import position, fully qualify the name in-place.
                item.setTextEdit(textEdit(w.sym.fullNameSyntax + suffix))
              case Some(value) =>
                val (short, edits) = ShortenedNames.synthesize(
                  TypeRef(
                    ThisType(w.sym.owner),
                    w.sym,
                    Nil
                  ),
                  pos,
                  context,
                  value
                )
                item.setAdditionalTextEdits(edits.asJava)
                item.setTextEdit(textEdit(short + suffix))
            }
          case _ if isImportPosition(pos) => // no parameter lists in import statements
          case member =>
            val baseLabel = ident
            if (isSnippet && member.sym.isNonNullaryMethod) {
              member.sym.paramss match {
                case Nil =>
                case Nil :: Nil =>
                  item.setTextEdit(textEdit(baseLabel + "()"))
                case head :: Nil if head.forall(_.isImplicit) =>
                  () // Don't set ($0) snippet for implicit-only params.
                case _ =>
                  if (clientSupportsSnippets) {
                    item.setTextEdit(textEdit(baseLabel + "($0)"))
                  }
                  metalsConfig
                    .parameterHintsCommand()
                    .asScala
                    .foreach { command =>
                      item.setCommand(
                        new l.Command("", command)
                      )
                    }
              }
            } else if (!suffix.isEmpty) {
              item.setTextEdit(textEdit(baseLabel + suffix))
            }
        }

        if (item.getTextEdit == null) {
          val editText = member match {
            case _: NamedArgMember => item.getLabel
            case _ => ident
          }
          item.setTextEdit(textEdit(editText))
        }

        member match {
          case o: TextEditMember =>
            o.command.foreach { command =>
              item.setCommand(new l.Command("", command))
            }
          case _ =>
        }

        val completionItemDataKind = member match {
          case o: OverrideDefMember if o.sym.isJavaDefined =>
            CompletionItemData.OverrideKind
          case _ =>
            null
        }

        item.setData(
          CompletionItemData(
            semanticdbSymbol(member.sym),
            buildTargetIdentifier,
            kind = completionItemDataKind
          ).toJson
        )
        item.setKind(completionItemKind(member))
        item.setSortText(f"${idx}%05d")
        if (member.sym.isDeprecated) {
          item.setTags(List(CompletionItemTag.Deprecated).asJava)
        }
        // NOTE: We intentionally don't set the commit character because there are valid scenarios where
        // the user wants to type a dot '.' character without selecting a completion item.
        if (idx == 0) {
          item.setPreselect(true)
        }
        item
    }

    val result = new CompletionList(items.toSeq.asJava)
    result.setIsIncomplete(i.isIncomplete)
    result
  }

  case class InterestingMembers(
      results: List[Member],
      searchResult: SymbolSearch.Result
  ) {
    def isIncomplete: Boolean = searchResult == SymbolSearch.Result.INCOMPLETE
  }

  private def filterInteresting(
      completions: List[Member],
      kind: CompletionListKind,
      query: String,
      pos: Position,
      completion: CompletionPosition,
      editRange: l.Range,
      latestParentTrees: List[Tree]
  ): InterestingMembers = {
    val isSeen = mutable.Set.empty[String]
    val isIgnored = mutable.Set.empty[Symbol]
    val buf = List.newBuilder[Member]
    def visit(head: Member): Boolean = {
      val id =
        if (head.sym.isClass || head.sym.isModule) {
          head.sym.fullName
        } else {
          head match {
            case o: OverrideDefMember =>
              o.label
            case _ =>
              semanticdbSymbol(head.sym)
          }
        }
      def isIgnoredWorkspace: Boolean =
        head.isInstanceOf[WorkspaceMember] &&
          (isIgnored(head.sym) || isIgnored(head.sym.companion))
      def isNotLocalForwardReference: Boolean =
        !head.sym.isLocalToBlock ||
          !head.sym.pos.isAfter(pos)
      if (
        !isSeen(id) &&
        !isUninterestingSymbol(head.sym) &&
        !isUninterestingSymbolOwner(head.sym.owner) &&
        !isIgnoredWorkspace &&
        completion.isCandidate(head) &&
        !head.sym.name.containsName(CURSOR) &&
        isNotLocalForwardReference
      ) {
        isSeen += id
        buf += head
        isIgnored ++= dealiasedValForwarder(head.sym)
        isIgnored ++= dealiasedType(head.sym)
        true
      } else {
        false
      }
    }
    completions.foreach(visit)
    completion.contribute.foreach(visit)
    buf ++= keywords(pos, editRange, latestParentTrees)
    val searchResults =
      if (kind == CompletionListKind.Scope) {
        workspaceSymbolListMembers(query, pos, visit)
      } else {
        SymbolSearch.Result.COMPLETE
      }

    InterestingMembers(buf.result(), searchResults)
  }

  private def isFunction(symbol: Symbol): Boolean = {
    compiler.definitions.isFunctionSymbol(
      symbol.info.finalResultType.typeSymbol
    )
  }

  private def completionItemKind(r: Member): CompletionItemKind = {
    import org.eclipse.lsp4j.{CompletionItemKind => k}
    val symbol = r.sym.dealiased
    val symbolIsFunction = isFunction(symbol)
    if (symbol.hasPackageFlag) k.Module
    else if (symbol.isPackageObject) k.Module
    else if (symbol.isModuleOrModuleClass) k.Module
    else if (symbol.isTrait) k.Interface
    else if (symbol.isJava) k.Interface
    else if (symbol.isClass) k.Class
    else if (symbol.isMethod) k.Method
    else if (symbol.isCaseAccessor) k.Field
    else if (symbol.isVal && !symbolIsFunction) k.Value
    else if (symbol.isVar && !symbolIsFunction) k.Variable
    else if (symbol.isTypeParameterOrSkolem) k.TypeParameter
    else if (symbolIsFunction) k.Function
    else k.Value
  }

  private def getLastVisitedParentTrees(pos: Position): List[Tree] = {
    if (lastVisitedParentTrees.isEmpty) {
      locateTree(pos)
    }
    lastVisitedParentTrees
  }

  private def safeCompletionsAt(
      pos: Position
  ): (InterestingMembers, CompletionPosition, l.Range, String) = {
    lazy val editRange = pos
      .withStart(inferIdentStart(pos, params.text()))
      .withEnd(pos.point)
      .toLSP
    val noQuery = "$a"
    def expected(e: Throwable) = {
      completionPosition(
        pos,
        params.text(),
        editRange,
        CompletionResult.NoResults,
        getLastVisitedParentTrees(pos)
      ) match {
        case NoneCompletion =>
          logger.warning(e.getMessage)
          (
            InterestingMembers(Nil, SymbolSearch.Result.COMPLETE),
            NoneCompletion,
            editRange,
            noQuery
          )
        case completion =>
          (
            InterestingMembers(
              completion.contribute,
              SymbolSearch.Result.COMPLETE
            ),
            completion,
            editRange,
            noQuery
          )
      }
    }
    try {
      val completions = completionsAt(pos) match {
        case CompletionResult.NoResults =>
          new DynamicFallbackCompletions(pos).print()
        case r => r
      }
      val kind = completions match {
        case _: CompletionResult.ScopeMembers =>
          CompletionListKind.Scope
        case _: CompletionResult.TypeMembers =>
          CompletionListKind.Type
        case _ =>
          CompletionListKind.None
      }
      val isTypeMember = kind == CompletionListKind.Type
      params.checkCanceled()
      val matchingResults = completions.matchingResults { entered => name =>
        val decoded = entered.decoded

        /**
         * NOTE(tgodzik): presentation compiler bug https://github.com/scala/scala/pull/8193
         *  should be removed once we drop support for 2.12.8 and 2.13.0
         *  in case we have a comment presentation compiler will see it as the name
         *  CompletionIssueSuite.issue-813 for more details
         */
        val realEntered =
          if (decoded.startsWith("//") || decoded.startsWith("/*")) { // start of a comment
            // we reverse the situation and look for the word from start of complition to either '.' or ' '
            val reversedString = decoded.reverse
            val lastSpace = reversedString.indexOfSlice(" ")
            val lastDot = reversedString.indexOfSlice(".")
            val startOfWord =
              if (lastSpace < lastDot && lastSpace >= 0) lastSpace else lastDot
            reversedString.slice(0, startOfWord).reverse
          } else {
            entered.toString
          }
        if (isTypeMember)
          CompletionFuzzy.matchesSubCharacters(realEntered, name)
        else CompletionFuzzy.matches(realEntered, name)
      }

      val latestParentTrees = getLastVisitedParentTrees(pos)
      val completion = completionPosition(
        pos,
        params.text(),
        editRange,
        completions,
        latestParentTrees
      )
      val query = completions.name.toString
      val items = filterInteresting(
        matchingResults,
        kind,
        query,
        pos,
        completion,
        editRange,
        latestParentTrees
      )
      params.checkCanceled()
      (items, completion, editRange, query)
    } catch {
      case e: CyclicReference
          if e.getMessage.contains("illegal cyclic reference") =>
        expected(e)
      case e: ScalaReflectionException
          if e.getMessage.contains("not a module") =>
        expected(e)
      case e: NullPointerException =>
        expected(e)
      case e: StringIndexOutOfBoundsException =>
        expected(e)
    }
  }

  private def workspaceSymbolListMembers(
      query: String,
      pos: Position,
      visit: Member => Boolean
  ): SymbolSearch.Result = {
    if (query.isEmpty) SymbolSearch.Result.INCOMPLETE
    else {
      val context = doLocateContext(pos)
      val visitor = new CompilerSearchVisitor(
        query,
        context,
        sym => visit(new WorkspaceMember(sym))
      )
      search.search(query, buildTargetIdentifier, visitor)
    }
  }

  /**
   * Get the missing implements and imports for the symbol at the given position.
   *
   * @return the list of TextEdits for missing implements and imports.
   */
  def implementAll(): ju.List[l.TextEdit] = {
    val unit = addCompilationUnit(
      code = params.text,
      filename = params.uri().toString(),
      cursor = None
    )
    val pos = unit.position(params.offset)
    implementAllAt(pos, params.text).asJava
  }

}
