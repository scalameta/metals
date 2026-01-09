package scala.meta.internal.pc

import java.net.URI
import java.{util => ju}

import scala.annotation.tailrec
import scala.collection.mutable

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.PcQueryContext
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
)(implicit val queryInfo: PcQueryContext) {
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
    val filename = params.uri().toString()
    val unit = addCompilationUnit(
      code = params.text,
      filename = filename,
      cursor = Some(params.offset),
      cursorName = cursorName
    )

    val pos = unit.position(params.offset)
    val isSnippet = isSnippetEnabled(pos, params.text())

    val (i, completion, editRange, query) = safeCompletionsAt(pos, params.uri())

    val start = inferIdentStart(pos, params.text())
    val end = inferIdentEnd(pos, params.text())
    val oldText = params.text().substring(start, end)
    val stripSuffix = pos.withStart(start).withEnd(end).toLsp

    def textEdit(newText: String, range: l.Range = editRange): l.TextEdit = {
      if (newText == oldText) new l.TextEdit(stripSuffix, newText)
      else new l.TextEdit(range, newText)
    }

    val history = new ShortenedNames()

    val sorted = i.results.sorted(memberOrdering(query, history, completion))
    lazy val importPosition = autoImportPosition(pos, params.text())
    lazy val context = doLocateImportContext(pos)

    @tailrec
    def findNonConflictingPrefix(sym: Symbol, acc: List[String]): String = {
      val symName = if (sym.name.isTermName) sym.name.dropLocal else sym.name
      context.lookupSymbol(symName, _ => true) match {
        case LookupSucceeded(_, symbol)
            if sym.effectiveOwner.exists && symbol.effectiveOwner.exists &&
              !symbol.effectiveOwner.isAnonymousFunction &&
              symbol.effectiveOwner != sym.effectiveOwner =>
          findNonConflictingPrefix(
            sym.effectiveOwner,
            Identifier.backtickWrap(sym.effectiveOwner.name.decoded) :: acc
          )
        case _ => acc.mkString(".")
      }
    }

    val items = sorted.iterator.zipWithIndex.map { case (member, idx) =>
      params.checkCanceled()
      val symbolName = member.symNameDropLocal.decoded
      val simpleIdent = Identifier.backtickWrap(symbolName)
      val ident =
        member match {
          case _: WorkspaceMember | _: WorkspaceImplicitMember |
              _: NamedArgMember | _: DependecyMember =>
            simpleIdent
          case _: ScopeMember =>
            findNonConflictingPrefix(member.sym, List(simpleIdent))
          case _ => simpleIdent
        }

      val detail = member match {
        case o: OverrideDefMember => o.detail
        case t: TextEditMember if t.detail.isDefined => t.detail.get
        case _ => detailString(member, history)
      }

      val includeDetailInLabel = compiler.metalsConfig.isDetailIncludedInLabel
      def labelWithSig =
        if (
          includeDetailInLabel && (member.sym.isMethod || member.sym.isValue)
        ) {
          ident + detail
        } else {
          ident
        }
      val label = member match {
        case _: NamedArgMember =>
          val escaped = if (isSnippet) ident.replace("$", "$$") else ident
          s"$escaped = "
        case o: OverrideDefMember =>
          o.label
        case d: DependecyMember =>
          d.label
        case o: TextEditMember =>
          o.label.getOrElse(labelWithSig)
        case _: WorkspaceImplicitMember =>
          s"$labelWithSig (implicit)"
        case o: WorkspaceMember if includeDetailInLabel =>
          s"$ident - ${o.sym.owner.fullName}"
        case _ => labelWithSig
      }
      val item = new CompletionItem(label)
      if (metalsConfig.isCompletionItemDetailEnabled && !detail.isEmpty()) {
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
        case i: DependecyMember =>
          item.setFilterText(i.dependency)
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
        case d: DependecyMember =>
          item.setTextEdit(d.edit)
        case m: WorkspaceImplicitMember =>
          val impPos = importPosition.getOrElse(AutoImportPosition(0, 0, false))
          val suffix =
            if (
              clientSupportsSnippets && m.sym.paramss.headOption.exists(
                _.nonEmpty
              )
            ) "($0)"
            else ""
          val (short, edits) = ShortenedNames.synthesize(
            TypeRef(
              ThisType(m.sym.owner),
              m.sym,
              Nil
            ),
            pos,
            context,
            impPos
          )
          val edit: l.TextEdit = textEdit(
            short + suffix,
            editRange
          )
          item.setTextEdit(edit)
          item.setAdditionalTextEdits(edits.asJava)
        case w: WorkspaceMember =>
          def createTextEdit(identifier: String) =
            textEdit(w.wrap(identifier), w.editRange.getOrElse(editRange))

          importPosition match {
            case None =>
              if (w.additionalTextEdits.nonEmpty) {
                item.setAdditionalTextEdits(w.additionalTextEdits.asJava)
              }
              // No import position, fully qualify the name in-place.
              item.setTextEdit(createTextEdit(w.sym.fullNameSyntax + suffix))
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
              item.setAdditionalTextEdits(
                (edits ++ w.additionalTextEdits).asJava
              )
              item.setTextEdit(createTextEdit(short + suffix))
          }
        case _
            if isImportPosition(
              pos
            ) => // no parameter lists in import statements
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
        case _: ImplementAllMember =>
          CompletionItemData.ImplementAllKind
        case o: OverrideDefMember if o.sym.isJavaDefined =>
          CompletionItemData.OverrideKind
        case _ =>
          CompletionItemData.None
      }

      val additionalSymbols = member match {
        case oam: ImplementAllMember =>
          oam.additionalSymbols.map(semanticdbSymbol).asJava
        case _ =>
          null
      }

      item.setData(
        CompletionItemData(
          semanticdbSymbol(member.sym),
          buildTargetIdentifier,
          kind = completionItemDataKind,
          additionalSymbols
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

    // we need to force the list to be fully evaluated instead of an iterator, or else
    // we risk having race conditions when infoString and type completers are invoked
    // on the LSP request thread
    val result = new CompletionList(items.toList.asJava)
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
      latestParentTrees: List[Tree],
      text: String
  ): InterestingMembers = {
    lazy val isAmmoniteScript = pos.source.file.name.isAmmoniteGeneratedFile
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
            case named: NamedArgMember =>
              s"named-${semanticdbSymbol(named.sym)}"
            case dep: DependecyMember =>
              s"dep-${dep.label}"
            case edit: TextEditMember =>
              s"edit-${edit.filterText}-${edit.label.getOrElse("")}"
            case _ =>
              semanticdbSymbol(head.sym)
          }
        }

      def isIgnoredWorkspace: Boolean =
        head.isInstanceOf[WorkspaceMember] &&
          (isIgnored(head.sym) || isIgnored(head.sym.companion))
      def isNotLocalForwardReference: Boolean =
        !head.sym.isLocalToBlock ||
          !head.sym.pos.isAfter(pos) ||
          head.sym.isParameter

      def isFileAmmoniteCompletion() =
        isAmmoniteScript && {
          head match {
            /* By default Scala compiler tries to suggest completions based on
             * generated file in `$file`, which is not valid from Ammonite point of view
             * We create other completions as ScopeMember in AmmoniteFileCompletions and
             * filter out the default ones here.
             */
            case _: TypeMember =>
              latestParentTrees.headOption.exists(tree =>
                isAmmoniteFileCompletionPosition(tree, pos)
              )
            case _ =>
              false
          }
        }

      if (
        !isSeen(id) &&
        !isUninterestingSymbol(head.sym) &&
        !isUninterestingSymbolOwner(head.sym.owner) &&
        !isIgnoredWorkspace &&
        !isFileAmmoniteCompletion() &&
        completion.isCandidate(head) &&
        !head.sym.name.containsName(CURSOR) &&
        isNotLocalForwardReference &&
        !isAliasCompletion(head) &&
        !head.sym.isPackageObjectOrClass
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
    completion.contribute.foreach(visit)
    completions.foreach(visit)
    buf ++= keywords(
      pos,
      editRange,
      latestParentTrees,
      completion,
      text,
      isAmmoniteScript
    )

    val searchResults =
      if (kind == CompletionListKind.Scope) {
        workspaceSymbolListMembers(query, pos, visit)
      } else {
        typedTreeAt(pos) match {
          case Select(qualifier, _)
              if qualifier.tpe != null && !qualifier.tpe.isError =>
            workspaceExtensionMethods(query, pos, visit, qualifier.tpe)
          case _ => SymbolSearch.Result.COMPLETE
        }
      }

    InterestingMembers(buf.result(), searchResults)
  }

  private def workspaceExtensionMethods(
      query: String,
      pos: Position,
      visit: Member => Boolean,
      selectType: Type
  ): SymbolSearch.Result = {
    val context = doLocateContext(pos)
    val visitor = new CompilerSearchVisitor(
      context,
      sym =>
        if (sym.safeOwner.isImplicit && sym.owner.isStatic) {
          val ownerConstructor = sym.owner.info.member(nme.CONSTRUCTOR)
          def typeParams = sym.owner.info.typeParams
          ownerConstructor.info.paramss match {
            case List(List(param))
                if selectType <:< boundedWildcardType(param.info, typeParams) =>
              visit(new WorkspaceImplicitMember(sym))
            case _ => false
          }
        } else false
    )
    search.searchMethods(query, buildTargetIdentifier, visitor)
  }

  private def isFunction(symbol: Symbol): Boolean = {
    compiler.definitions.isFunctionSymbol(
      symbol.info.finalResultType.typeSymbol
    )
  }

  private def completionItemKind(member: Member): CompletionItemKind = {
    import org.eclipse.lsp4j.{CompletionItemKind => k}
    val symbol = member.sym.dealiased
    val symbolIsFunction = isFunction(symbol)
    if (symbol.hasPackageFlag) k.Module
    else if (symbol.isPackageObject) k.Module
    else if (symbol.isModuleOrModuleClass) k.Module
    else if (symbol.isTrait) k.Interface
    else if (symbol.isJava) k.Interface
    else if (symbol.isClass) k.Class
    else if (symbol.isMethod) k.Method
    else if (symbol.isCaseAccessor) k.Field
    else if (symbol.isVal && !symbolIsFunction)
      member match {
        case file: FileSystemMember =>
          if (file.isDirectory) k.Folder else k.File
        case _ => k.Value
      }
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
      pos: Position,
      source: URI
  ): (InterestingMembers, CompletionPosition, l.Range, String) = {
    lazy val editRange = pos
      .withStart(inferIdentStart(pos, params.text()))
      .withEnd(pos.point)
      .toLsp
    val noQuery = "$a"
    def expected(e: Throwable) = {
      completionPosition(
        pos,
        source,
        params.text(),
        editRange,
        CompletionResult.NoResults,
        getLastVisitedParentTrees(pos)
      ) match {
        case NoneCompletion =>
          logger.warn(e.getMessage)
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
        if (isTypeMember) CompletionFuzzy.matchesSubCharacters(entered, name)
        else CompletionFuzzy.matches(entered, name)
      }

      val latestParentTrees = getLastVisitedParentTrees(pos)
      val completion = completionPosition(
        pos,
        source,
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
        latestParentTrees,
        params.text()
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
