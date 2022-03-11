package scala.meta.internal.pc
package completions

import scala.collection.mutable

import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.internal.pc.IdentifierComparator
import scala.meta.internal.pc.completions.KeywordsCompletions
import scala.meta.pc.*

import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.Flags.*
import dotty.tools.dotc.core.NameOps.*
import dotty.tools.dotc.core.Names.*
import dotty.tools.dotc.core.StdNames.*
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.interactive.Completion
import dotty.tools.dotc.transform.SymUtils.*
import dotty.tools.dotc.util.NameTransformer
import dotty.tools.dotc.util.NoSourcePosition
import dotty.tools.dotc.util.SourcePosition
import dotty.tools.dotc.util.Spans
import dotty.tools.dotc.util.SrcPos

class Completions(
    pos: SourcePosition,
    ctx: Context,
    search: SymbolSearch,
    buildTargetIdentifier: String,
    completionPos: CompletionPos,
    indexedContext: IndexedContext,
    path: List[Tree]
):

  implicit val context: Context = ctx

  def completions(): (List[CompletionValue], SymbolSearch.Result) =
    val (_, compilerCompletions) = Completion.completions(pos)

    val (completions, result) = path match
      // should not show completions for toplevel
      case Nil if pos.source.file.extension != "sc" =>
        (List.empty, SymbolSearch.Result.COMPLETE)
      case _ =>
        compilerCompletions
          .flatMap(CompletionValue.fromCompiler)
          .filterInteresting()

    val args = NamedArgCompletions.contribute(pos, path)
    val keywords = KeywordsCompletions.contribute(path, completionPos)
    val all = completions ++ args ++ keywords
    val application = CompletionApplication.fromPath(path)
    val ordering = completionOrdering(application)
    val values = application.postProcess(all.sorted(ordering))
    (values, result)
  end completions

  private def description(sym: Symbol): String =
    if sym.isType then sym.showFullName
    else sym.info.widenTermRefExpr.show

  private def enrichWithSymbolSearch(
      visit: CompletionValue => Boolean
  ): Option[SymbolSearch.Result] =
    val query = completionPos.query
    completionPos.kind match
      case CompletionKind.Empty =>
        val filtered = indexedContext.scopeSymbols
          .filter(sym => !sym.is(Synthetic) && !sym.isConstructor)

        filtered.map { sym =>
          visit(CompletionValue.scope(sym.decodedName, sym))
        }
        Some(SymbolSearch.Result.INCOMPLETE)
      case CompletionKind.Scope =>
        val visitor = new CompilerSearchVisitor(
          query,
          sym =>
            val value =
              indexedContext.lookupSym(sym) match
                case IndexedContext.Result.InScope =>
                  CompletionValue.scope(sym.decodedName, sym)
                case _ => CompletionValue.workspace(sym.decodedName, sym)
            visit(value)
        )
        Some(search.search(query, buildTargetIdentifier, visitor))
      case _ => None
    end match
  end enrichWithSymbolSearch

  extension (s: SrcPos)
    def isAfter(s1: SrcPos) =
      s.sourcePos.exists && s1.sourcePos.exists && s.sourcePos.point > s1.sourcePos.point

  extension (sym: Symbol)
    def detailString: String =
      if sym.is(Method) then
        val sig = sym.signature
        val sigString =
          if sig.paramsSig.isEmpty then "()"
          else
            sig.paramsSig
              .map(p => p.toString)
              .mkString("(", ",", ")")
        sym.showFullName + sigString
      else sym.fullName.stripModuleClassSuffix.show

  extension (l: List[CompletionValue])
    def filterInteresting(): (List[CompletionValue], SymbolSearch.Result) =

      val isSeen = mutable.Set.empty[String]
      val buf = List.newBuilder[CompletionValue]
      def visit(head: CompletionValue): Boolean =
        val (id, include) =
          head match
            case symOnly: CompletionValue.Symbolic =>
              val sym = symOnly.symbol
              val name = SemanticdbSymbols.symbolName(sym)
              val id =
                if sym.isClass || sym.is(Module) then
                  // drop #|. at the end to avoid duplication
                  name.substring(0, name.length - 1)
                else name

              val include =
                !isUninterestingSymbol(sym) &&
                  isNotLocalForwardReference(sym)

              (id, include)
            case kw: CompletionValue.Keyword => (kw.label, true)
            case namedArg: CompletionValue.NamedArg =>
              val id = namedArg.label + "="
              (id, true)

        if !isSeen(id) && include then
          isSeen += id
          buf += head
          true
        else false
      end visit

      l.foreach(visit)
      val searchResult =
        enrichWithSymbolSearch(visit).getOrElse(SymbolSearch.Result.COMPLETE)
      (buf.result, searchResult)

  private lazy val isUninterestingSymbol: Set[Symbol] = Set[Symbol](
    defn.Any_==,
    defn.Any_!=,
    defn.Any_##,
    defn.Object_eq,
    defn.Object_ne,
    defn.RepeatedParamClass,
    defn.ByNameParamClass2x,
    defn.Object_notify,
    defn.Object_notifyAll,
    defn.Object_notify,
    defn.Predef_undefined,
    defn.ObjectClass.info.member(nme.wait_).symbol,
    // NOTE(olafur) IntelliJ does not complete the root package and without this filter
    // then `_root_` would appear as a completion result in the code `foobar(_<COMPLETE>)`
    defn.RootPackage,
    // NOTE(gabro) valueOf was added as a Predef member in 2.13. We filter it out since is a niche
    // use case and it would appear upon typing 'val'
    defn.ValueOfClass.info.member(nme.valueOf).symbol,
    defn.ScalaPredefModule.requiredMethod(nme.valueOf)
  ).flatMap(_.alternatives.map(_.symbol)).toSet

  private def isNotLocalForwardReference(sym: Symbol)(using Context): Boolean =
    !sym.isLocalToBlock ||
      !sym.srcPos.isAfter(pos) ||
      sym.is(Param)

  private def computeRelevancePenalty(
      completion: CompletionValue,
      application: CompletionApplication
  ): Int =
    import scala.meta.internal.pc.MemberOrdering.*

    def hasGetter(sym: Symbol) = try
      def isModuleOrClass = sym.is(Module) || sym.isClass
      // isField returns true for some classes
      def isJavaClass = sym.is(JavaDefined) && isModuleOrClass
      (sym.isField && !isJavaClass && !isModuleOrClass) || sym.getter != NoSymbol
    catch case _ => false

    def symbolRelevance(sym: Symbol): Int =
      var relevance = 0
      // symbols defined in this file are more relevant
      if pos.source != sym.source || sym.is(Package) then
        relevance |= IsNotDefinedInFile
      // fields are more relevant than non fields
      if !hasGetter(sym) then relevance |= IsNotGetter
      // symbols whose owner is a base class are less relevant
      if sym.owner == defn.AnyClass || sym.owner == defn.ObjectClass
      then relevance |= IsInheritedBaseMethod
      // symbols not provided via an implicit are more relevant
      if sym.is(Implicit) ||
        sym.is(ExtensionMethod) ||
        application.isImplicitConversion(sym)
      then relevance |= IsImplicitConversion
      if application.isInherited(sym) then relevance |= IsInherited
      if sym.is(Package) then relevance |= IsPackage
      // accessors of case class members are more relevant
      if !sym.is(CaseAccessor) then relevance |= IsNotCaseAccessor
      // public symbols are more relevant
      if !sym.isPublic then relevance |= IsNotCaseAccessor
      // synthetic symbols are less relevant (e.g. `copy` on case classes)
      if sym.is(Synthetic) && !sym.isAllOf(EnumCase) then
        relevance |= IsSynthetic
      if sym.isDeprecated then relevance |= IsDeprecated
      if isEvilMethod(sym.name) then relevance |= IsEvilMethod

      relevance
    end symbolRelevance

    completion match
      case CompletionValue.Workspace(_, sym) =>
        symbolRelevance(sym) | (IsWorkspaceSymbol + sym.name.show.length)
      case sym: CompletionValue.Symbolic =>
        symbolRelevance(sym.symbol)
      case _ =>
        Int.MaxValue
  end computeRelevancePenalty

  private lazy val isEvilMethod: Set[Name] = Set[Name](
    nme.notifyAll_,
    nme.notify_,
    nme.wait_,
    nme.clone_,
    nme.finalize_
  )

  trait CompletionApplication:
    def isImplicitConversion(symbol: Symbol): Boolean
    def isMember(symbol: Symbol): Boolean
    def isInherited(symbol: Symbol): Boolean
    def postProcess(items: List[CompletionValue]): List[CompletionValue]

  object CompletionApplication:
    val empty = new CompletionApplication:
      def isImplicitConversion(symbol: Symbol): Boolean = false
      def isMember(symbol: Symbol): Boolean = false
      def isInherited(symbol: Symbol): Boolean = false
      def postProcess(items: List[CompletionValue]): List[CompletionValue] =
        items

    def forSelect(sel: Select): CompletionApplication =
      val tpe = sel.qualifier.tpe
      val members = tpe.allMembers.map(_.symbol).toSet

      new CompletionApplication:
        def isImplicitConversion(symbol: Symbol): Boolean =
          !isMember(symbol)
        def isMember(symbol: Symbol): Boolean = members.contains(symbol)
        def isInherited(symbol: Symbol): Boolean =
          isMember(symbol) && symbol.owner != tpe.typeSymbol
        def postProcess(items: List[CompletionValue]): List[CompletionValue] =
          items.map {
            case CompletionValue.Compiler(label, sym)
                if sym.info.paramNamess.nonEmpty && isMember(sym) =>
              CompletionValue.Compiler(label, substituteTypeVars(sym))
            case other => other
          }

        private def substituteTypeVars(symbol: Symbol): Symbol =
          val denot = symbol.asSeenFrom(tpe)
          symbol.withUpdatedTpe(denot.info)

      end new
    end forSelect

    def fromPath(path: List[Tree]): CompletionApplication =
      path.headOption match
        case Some(Select(qual @ This(_), _)) if qual.span.isSynthetic => empty
        case Some(select: Select) => forSelect(select)
        case _ => empty

  end CompletionApplication

  private def completionOrdering(
      application: CompletionApplication
  ): Ordering[CompletionValue] =
    new Ordering[CompletionValue]:
      val queryLower = completionPos.query.toLowerCase()
      val fuzzyCache = mutable.Map.empty[Symbol, Int]

      def compareLocalSymbols(s1: Symbol, s2: Symbol): Int =
        if s1.isLocal && s2.isLocal then
          val firstIsAfter = s1.srcPos.isAfter(s2.srcPos)
          if firstIsAfter then -1 else 1
        else 0
      end compareLocalSymbols

      def compareByRelevance(o1: CompletionValue, o2: CompletionValue): Int =
        Integer.compare(
          computeRelevancePenalty(o1, application),
          computeRelevancePenalty(o2, application)
        )

      def fuzzyScore(o: Symbol): Int =
        fuzzyCache.getOrElseUpdate(
          o, {
            val name = o.name.toString().toLowerCase()
            if name.startsWith(queryLower) then 0
            else if name.toLowerCase().contains(queryLower) then 1
            else 2
          }
        )

      /**
       * This one is used for the following case:
       * ```scala
       * def foo(argument: Int): Int = ???
       * val argument = 42
       * foo(arg@@) // completions should be ordered as :
       *            // - argument       (local val) - actual value comes first
       *            // - argument = ... (named arg) - named arg after
       *            // - ... all other options
       * ```
       */
      def compareInApplyParams(o1: CompletionValue, o2: CompletionValue): Int =
        def priority(v: CompletionValue): Int =
          v match
            case _: CompletionValue.Compiler => 0
            case _: CompletionValue.NamedArg => 1
            case _ => 2

        priority(o1) - priority(o2)
      end compareInApplyParams

      override def compare(o1: CompletionValue, o2: CompletionValue): Int =
        (o1, o2) match
          case (
                sym1: CompletionValue.Symbolic,
                sym2: CompletionValue.Symbolic
              ) =>
            val s1 = sym1.symbol
            val s2 = sym2.symbol
            val byLocalSymbol = compareLocalSymbols(s1, s2)
            if byLocalSymbol != 0 then byLocalSymbol
            else
              val byRelevance = compareByRelevance(o1, o2)
              if byRelevance != 0 then byRelevance
              else
                val byFuzzy = Integer.compare(
                  fuzzyScore(s1),
                  fuzzyScore(s2)
                )
                if byFuzzy != 0 then byFuzzy
                else
                  val byIdentifier = IdentifierComparator.compare(
                    s1.name.show,
                    s2.name.show
                  )
                  if byIdentifier != 0 then byIdentifier
                  else
                    val byOwner =
                      s1.owner.fullName.toString
                        .compareTo(s2.owner.fullName.toString)
                    if byOwner != 0 then byOwner
                    else
                      val byParamCount = Integer.compare(
                        s1.paramSymss.flatten.size,
                        s2.paramSymss.flatten.size
                      )
                      if byParamCount != 0 then byParamCount
                      else s1.detailString.compareTo(s2.detailString)
                end if
              end if
            end if
          case _ =>
            val byApplyParams = compareInApplyParams(o1, o2)
            if byApplyParams != 0 then byApplyParams
            else compareByRelevance(o1, o2)

      end compare

end Completions
