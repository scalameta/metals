package scala.meta.internal.pc

import java.nio.file.Path
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.meta.internal.metals.Fuzzy
import scala.meta.pc.CompletionItems
import scala.meta.pc.CompletionItems.LookupKind
import scala.meta.pc.OffsetParams
import scala.meta.pc.SymbolSearch
import scala.util.control.NonFatal
import scala.meta.internal.semanticdb.Scala._
import scala.meta.pc.SymbolSearchVisitor
import org.eclipse.{lsp4j => l}

class CompletionProvider(
    val compiler: MetalsGlobal,
    params: OffsetParams
) {
  import compiler._

  val maxWorkspaceSymbolResults = 10

  def completions(): CompletionItems = {
    val unit = addCompilationUnit(
      code = params.text,
      filename = params.filename,
      cursor = Some(params.offset)
    )
    val position = unit.position(params.offset)
    val (qual, kind, i) = safeCompletionsAt(position)
    val history = new ShortenedNames()
    val sorted = i.results.sorted(memberOrdering(qual, history))
    val items = sorted.iterator.zipWithIndex.map {
      case (r, idx) =>
        params.checkCanceled()
        val label = r.symNameDropLocal.decoded
        val item = new CompletionItem(label)
        // TODO(olafur): investigate TypeMembers.prefix field, maybe it can replace qual match here.
        val detail = detailString(qual, r, history)
        r match {
          case w: WorkspaceMember =>
            item.setInsertText(w.sym.fullName)
          case _ =>
        }
        item.setDetail(detail)
        item.setData(
          CompletionItemData(semanticdbSymbol(r.sym), buildTargetIdentifier).toJson
        )
        item.setKind(completionItemKind(r))
        item.setSortText(f"${idx}%05d")
        if (r.sym.isDeprecated) {
          item.setDeprecated(true)
        }
        val commitCharacter =
          if (r.sym.isMethod && !isNullary(r.sym)) "("
          else "."
        item.setCommitCharacters(List(commitCharacter).asJava)
        if (idx == 0) {
          item.setPreselect(true)
        }
        item
    }
    val result = new CompletionItems(kind, items.toSeq.asJava)
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
      kind: LookupKind,
      query: String,
      pos: Position
  ): InterestingMembers = {
    val isUninterestingSymbol = Set[Symbol](
      // the methods == != ## are arguably "interesting" but they're here becuase
      // - they're short so completing them doesn't save you keystrokes
      // - they're available on everything so you
      definitions.Any_==,
      definitions.Any_!=,
      definitions.Any_##,
      definitions.Object_==,
      definitions.Object_!=,
      definitions.Object_##,
      definitions.Object_eq,
      definitions.Object_ne,
      definitions.RepeatedParamClass,
      definitions.ByNameParamClass,
      definitions.JavaRepeatedParamClass,
      definitions.Object_notify,
      definitions.Object_notifyAll,
      definitions.Object_notify,
      definitions.getMemberMethod(definitions.ObjectClass, termNames.wait_),
      definitions.getMemberMethod(
        definitions.getMemberClass(
          definitions.PredefModule,
          TypeName("ArrowAssoc")
        ),
        TermName("→").encode
      )
    ).flatMap(_.alternatives)
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
        !isIgnoredWorkspace) {
        isSeen += id
        buf += head
        isIgnored ++= dealiasedValForwarder(head.sym)
        true
      } else {
        false
      }
    }
    completions.foreach(visit)
    val searchResults =
      if (kind == LookupKind.Scope) {
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
    val symbol = r.sym
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
  ): (Option[Type], LookupKind, InterestingMembers) = {
    def expected(e: Throwable) = {
      logger.warning(e.getMessage)
      (
        None,
        LookupKind.None,
        InterestingMembers(Nil, SymbolSearch.Result.COMPLETE)
      )
    }
    try {
      val completions = completionsAt(position)
      params.checkCanceled()
      val matchingResults = completions.matchingResults { entered => name =>
        Fuzzy.matches(entered, name)
      }
      val kind = completions match {
        case _: CompletionResult.ScopeMembers =>
          LookupKind.Scope
        case _: CompletionResult.TypeMembers =>
          LookupKind.Type
        case _ =>
          LookupKind.None
      }
      val items = filterInteresting(
        matchingResults,
        kind,
        completions.name.toString,
        position
      )
      params.checkCanceled()
      val qual = completions match {
        case t: CompletionResult.TypeMembers =>
          Option(t.qualifier.tpe)
        case _ =>
          None
      }
      (qual, kind, items)
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
      params.checkCanceled()
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
