package scala.meta.internal.pc

import java.nio.file.Path
import scala.meta.internal.mtags.MtagsEnrichments._
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.InsertTextFormat
import scala.collection.JavaConverters._
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

  val maxWorkspaceSymbolResults = 10

  def completions(): CompletionList = {
    val unit = addCompilationUnit(
      code = params.text,
      filename = params.filename,
      cursor = Some(params.offset)
    )
    val pos = unit.position(params.offset)
    val (i, completion) = safeCompletionsAt(pos)
    val history = new ShortenedNames()
    val sorted = i.results.sorted(memberOrdering(history, completion))
    val items = sorted.iterator.zipWithIndex.map {
      case (r, idx) =>
        params.checkCanceled()
        val symbolName = r.symNameDropLocal.decoded
        val ident = Identifier.backtickWrap(symbolName)
        val label = r match {
          case _: NamedArgMember =>
            s"${ident} = "
          case o: OverrideDefMember =>
            o.label
          case _ =>
            ident
        }
        val item = new CompletionItem(label)
        val detail = r match {
          case o: OverrideDefMember => o.label
          case _ => detailString(r, history)
        }
        item.setDetail(detail)
        val templateSuffix =
          if (completion.isNew &&
            r.sym.dealiased.requiresTemplateCurlyBraces) " {}"
          else ""
        val typeSuffix =
          if (completion.isType && r.sym.dealiased.hasTypeParams) "[$0]"
          else if (completion.isNew && r.sym.dealiased.hasTypeParams) "[$0]"
          else ""
        val suffix = typeSuffix + templateSuffix

        r match {
          case i: TextEditMember =>
            item.setFilterText(i.filterText)
          case i: OverrideDefMember =>
            item.setFilterText(i.filterText)
          case _ =>
            if (ident.startsWith("`")) {
              item.setFilterText(symbolName)
            }
        }

        r match {
          case i: TextEditMember =>
            item.setTextEdit(i.edit)
            item.setInsertTextFormat(InsertTextFormat.Snippet)
          case i: OverrideDefMember =>
            item.setTextEdit(i.edit)
            item.setAdditionalTextEdits(i.autoImports.asJava)
            item.setInsertTextFormat(InsertTextFormat.Snippet)
          case w: WorkspaceMember =>
            item.setInsertTextFormat(InsertTextFormat.Snippet)
            item.setInsertText(w.sym.fullName + suffix)
          case _ =>
            if (r.sym.isNonNullaryMethod) {
              item.setInsertTextFormat(InsertTextFormat.Snippet)
              r.sym.paramss match {
                case Nil =>
                case Nil :: Nil =>
                  item.setInsertText(label + "()")
                case _ =>
                  item.setInsertText(label + "($0)")
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
              item.setInsertTextFormat(InsertTextFormat.Snippet)
              item.setInsertText(label + suffix)
            }
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
        // Commit character
        if (r.sym.isMethod && !isNullary(r.sym)) {
          // NOTE(olafur) don't use `(` as a commit character for methods because it conflicts with
          // the `($0)` snippet behavior resulting in a redundant unit literal: `println(())`.
          // The ideal solution would be to not use the `($0)` snippet when the commit character `(` is used,
          // however language servers can't distinguish what commit character is used. It works as
          // expected in IntelliJ.
        } else {
          item.setCommitCharacters(List(".").asJava)
        }
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
      completion: CompletionPosition
  ): InterestingMembers = {
    val isSeen = mutable.Set.empty[String]
    val isIgnored = mutable.Set.empty[Symbol]
    val buf = List.newBuilder[Member]
    def visit(head: Member): Boolean = {
      val id =
        if (head.sym.isClass || head.sym.isModule) {
          head.sym.fullName
        } else {
          semanticdbSymbol(head.sym)
        }
      def isIgnoredWorkspace: Boolean =
        head.isInstanceOf[WorkspaceMember] &&
          (isIgnored(head.sym) || isIgnored(head.sym.companion))
      if (!isSeen(id) &&
        !isUninterestingSymbol(head.sym) &&
        !isIgnoredWorkspace &&
        completion.isCandidate(head)) {
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

  private def safeCompletionsAt(
      position: Position
  ): (InterestingMembers, CompletionPosition) = {
    def expected(e: Throwable) = {
      completionPosition(position, params.text()) match {
        case CompletionPosition.None =>
          logger.warning(e.getMessage)
          (
            InterestingMembers(Nil, SymbolSearch.Result.COMPLETE),
            CompletionPosition.None
          )
        case completion =>
          (
            InterestingMembers(
              completion.contribute,
              SymbolSearch.Result.COMPLETE
            ),
            completion
          )
      }
    }
    try {
      val completions = completionsAt(position) match {
        case CompletionResult.NoResults =>
          new DynamicFallbackCompletions(position).print()
        case r => r
      }
      params.checkCanceled()
      val matchingResults = completions.matchingResults { entered => name =>
        CompletionFuzzy.matches(entered, name)
      }
      val kind = completions match {
        case _: CompletionResult.ScopeMembers =>
          CompletionListKind.Scope
        case _: CompletionResult.TypeMembers =>
          CompletionListKind.Type
        case _ =>
          CompletionListKind.None
      }
      val completion = completionPosition(position, params.text())
      val items = filterInteresting(
        matchingResults,
        kind,
        completions.name.toString,
        position,
        completion
      )
      params.checkCanceled()
      (items, completion)
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
    if (query.isEmpty) SymbolSearch.Result.COMPLETE
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
