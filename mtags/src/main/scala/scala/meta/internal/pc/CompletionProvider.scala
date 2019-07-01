package scala.meta.internal.pc

import java.nio.file.Path
import scala.meta.internal.mtags.MtagsEnrichments._
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.InsertTextFormat
import scala.meta.internal.jdk.CollectionConverters._
import scala.collection.mutable
import scala.meta.pc.OffsetParams
import scala.meta.pc.SymbolSearch
import scala.util.control.NonFatal
import scala.meta.pc.SymbolSearchVisitor
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
      case _ =>
        // Default _CURSOR_ instrumentation.
        CURSOR
    }
  }

  def completions(): CompletionList = {
    val unit = addCompilationUnit(
      code = params.text,
      filename = params.filename,
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
      case (r, idx) =>
        params.checkCanceled()
        val symbolName = r.symNameDropLocal.decoded
        val ident = Identifier.backtickWrap(symbolName)
        val detail = r match {
          case o: OverrideDefMember => o.detail
          case t: TextEditMember if t.detail.isDefined => t.detail.get
          case _ => detailString(r, history)
        }
        val label = r match {
          case _: NamedArgMember =>
            s"${ident} = "
          case o: OverrideDefMember =>
            o.label
          case o: TextEditMember =>
            o.label.getOrElse(ident)
          case o: WorkspaceMember =>
            s"$ident - ${o.sym.owner.fullName}"
          case _ =>
            if (r.sym.isMethod || r.sym.isValue) {
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
          if (!isSnippet) ""
          else if (completion.isNew &&
            r.sym.dealiased.requiresTemplateCurlyBraces) " {}"
          else ""
        val typeSuffix =
          if (!isSnippet) ""
          else if (completion.isType && r.sym.dealiased.hasTypeParams) "[$0]"
          else if (completion.isNew && r.sym.dealiased.hasTypeParams) "[$0]"
          else ""
        val suffix = typeSuffix + templateSuffix

        r match {
          case i: TextEditMember =>
            item.setFilterText(i.filterText)
          case i: OverrideDefMember =>
            item.setFilterText(i.filterText)
          case _ =>
            // Explicitly set filter text because the label has method signature and
            // fully qualified name.
            item.setFilterText(symbolName)
        }

        item.setInsertTextFormat(InsertTextFormat.Snippet)

        r match {
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
          case _ =>
            val baseLabel = ident
            if (isSnippet && r.sym.isNonNullaryMethod) {
              r.sym.paramss match {
                case Nil =>
                case Nil :: Nil =>
                  item.setTextEdit(textEdit(baseLabel + "()"))
                case head :: Nil if head.forall(_.isImplicit) =>
                  () // Don't set ($0) snippet for implicit-only params.
                case _ =>
                  item.setTextEdit(textEdit(baseLabel + "($0)"))
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
          val editText = r match {
            case _: NamedArgMember => item.getLabel
            case _ => ident
          }
          item.setTextEdit(textEdit(editText))
        }

        r match {
          case o: TextEditMember =>
            o.command.foreach { command =>
              item.setCommand(new l.Command("", command))
            }
          case _ =>
        }

        val completionItemDataKind = r match {
          case o: OverrideDefMember if o.sym.isJavaDefined =>
            CompletionItemData.OverrideKind
          case _ =>
            null
        }

        item.setData(
          CompletionItemData(
            semanticdbSymbol(r.sym),
            buildTargetIdentifier,
            kind = completionItemDataKind
          ).toJson
        )
        item.setKind(completionItemKind(r))
        item.setSortText(f"${idx}%05d")
        if (r.sym.isDeprecated) {
          item.setDeprecated(true)
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

  def isNullary(sym: Symbol): Boolean = sym.info match {
    case _: NullaryMethodType => true
    case PolyType(_, _: NullaryMethodType) => true
    case _ => false
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
      if (!isSeen(id) &&
        !isUninterestingSymbol(head.sym) &&
        !isUninterestingSymbolOwner(head.sym.owner) &&
        !isIgnoredWorkspace &&
        completion.isCandidate(head) &&
        !head.sym.name.containsName(CURSOR) &&
        isNotLocalForwardReference) {
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
    if (lastVisistedParentTrees.isEmpty) {
      locateTree(pos)
    }
    lastVisistedParentTrees
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
        case CompletionPosition.None =>
          logger.warning(e.getMessage)
          (
            InterestingMembers(Nil, SymbolSearch.Result.COMPLETE),
            CompletionPosition.None,
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
        /** NOTE(tgodzik): presentation compiler bug https://github.com/scala/scala/pull/8193
         *  should be removed once we drop support for 2.12.8 and 2.13.0
         *  in case we have a comment inline presentation compiler will see it as the name
         *  CompletionIssueSuite.issue-813 for more details
         * $div$div = '//'
         * $u000A = '\n'
         * $u0020 = ' '
         * $u002E = '.'
         */
        val enteredFixed =
          if (entered.startsWith("$div$div")) { // start of a comment: "//"
            entered.toString
              .replaceAll("\\$div\\$div.+\\$u000A", "") // entire one-line comment
              .replaceAll("(\\$u0020|\\$u002E)", "") // any space or dot
          } else {
            entered.toString
          }
        if (isTypeMember)
          CompletionFuzzy.matchesSubCharacters(enteredFixed, name)
        else CompletionFuzzy.matches(enteredFixed, name)
      }

      val latestParentTrees = getLastVisitedParentTrees(pos)
      val completion =
        completionPosition(
          pos,
          params.text(),
          editRange,
          completions,
          latestParentTrees
        )
      val query = completions.name.toString
      val items =
        filterInteresting(
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
      val visitor = new CompilerSearchVisitor(query, context, visit)
      search.search(query, buildTargetIdentifier, visitor)
    }
  }

  /**
   * Symbol search visitor that converts results into completion `WorkspaceMember`.
   */
  private class CompilerSearchVisitor(
      query: String,
      context: Context,
      visitMember: Member => Boolean
  ) extends SymbolSearchVisitor {
    def visit(top: SymbolSearchCandidate): Int = {
      var added = 0
      for {
        sym <- loadSymbolFromClassfile(top)
        if context.lookupSymbol(sym.name, _ => true).symbol != sym
      } {
        if (visitMember(new WorkspaceMember(sym))) {
          added += 1
        }
      }
      added
    }
    def visitClassfile(pkg: String, filename: String): Int = {
      visit(SymbolSearchCandidate.Classfile(pkg, filename))
    }
    def visitWorkspaceSymbol(
        path: Path,
        symbol: String,
        kind: l.SymbolKind,
        range: l.Range
    ): Int = {
      visit(SymbolSearchCandidate.Workspace(symbol))
    }

    def shouldVisitPackage(pkg: String): Boolean =
      packageSymbolFromString(pkg).isDefined

    override def isCancelled: Boolean = {
      false
    }
  }

  private def loadSymbolFromClassfile(
      classfile: SymbolSearchCandidate
  ): List[Symbol] = {
    def isAccessible(sym: Symbol): Boolean = {
      sym != NoSymbol && {
        sym.info // needed to fill complete symbol
        sym.isPublic
      }
    }
    try {
      classfile match {
        case SymbolSearchCandidate.Classfile(pkgString, filename) =>
          val pkg = packageSymbolFromString(pkgString).getOrElse(
            throw new NoSuchElementException(pkgString)
          )
          val names = filename
            .stripSuffix(".class")
            .split('$')
            .iterator
            .filterNot(_.isEmpty)
            .toList
          val members = names.foldLeft(List[Symbol](pkg)) {
            case (accum, name) =>
              accum.flatMap { sym =>
                if (!isAccessible(sym) || !sym.isModuleOrModuleClass) Nil
                else {
                  sym.info.member(TermName(name)) ::
                    sym.info.member(TypeName(name)) ::
                    Nil
                }
              }
          }
          members.filter(sym => isAccessible(sym))
        case SymbolSearchCandidate.Workspace(symbol) =>
          val gsym = inverseSemanticdbSymbol(symbol)
          if (isAccessible(gsym)) gsym :: Nil
          else Nil
      }
    } catch {
      case NonFatal(_) =>
        logger.warning(s"no such symbol: $classfile")
        Nil
    }
  }

}
