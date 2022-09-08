package scala.meta.internal.pc
package completions

import java.nio.file.Path
import java.nio.file.Paths

import scala.collection.mutable

import scala.meta.internal.metals.Fuzzy
import scala.meta.internal.mtags.BuildInfo
import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.internal.pc.IdentifierComparator
import scala.meta.internal.pc.completions.KeywordsCompletions
import scala.meta.internal.semver.SemVer
import scala.meta.pc.*

import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Denotations.*
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.Flags.*
import dotty.tools.dotc.core.NameOps.*
import dotty.tools.dotc.core.Names.*
import dotty.tools.dotc.core.StdNames
import dotty.tools.dotc.core.StdNames.*
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.interactive.Completion
import dotty.tools.dotc.transform.SymUtils.*
import dotty.tools.dotc.util.NameTransformer
import dotty.tools.dotc.util.NoSourcePosition
import dotty.tools.dotc.util.SourcePosition
import dotty.tools.dotc.util.Spans
import dotty.tools.dotc.util.Spans.Span
import dotty.tools.dotc.util.SrcPos

class Completions(
    pos: SourcePosition,
    text: String,
    ctx: Context,
    search: SymbolSearch,
    buildTargetIdentifier: String,
    completionPos: CompletionPos,
    indexedContext: IndexedContext,
    path: List[Tree],
    config: PresentationCompilerConfig,
    workspace: Option[Path],
):

  implicit val context: Context = ctx

  // versions prior to 3.1.0 sometimes didn't manage to detect properly Java objects
  val canDetectJavaObjectsCorrectly =
    SemVer.isLaterVersion("3.1.0", BuildInfo.scalaCompilerVersion)

  private lazy val shouldAddSnippet = path match
    /* In case of `method@@()` we should not add snippets and the path
     * will contain apply as the parent of the current tree.
     */
    case (fun) :: (appl: GenericApply) :: _ if appl.fun == fun =>
      false
    case (_: Import) :: _ => false
    case _ :: (_: Import) :: _ => false
    case (_: Ident) :: (_: SeqLiteral) :: _ => false
    case _ => true

  enum CursorPos:
    case Type(hasTypeParams: Boolean, hasNewKw: Boolean)
    case Term
    case Import

    def include(sym: Symbol)(using Context): Boolean =
      val generalExclude =
        isUninterestingSymbol(sym) ||
          !isNotLocalForwardReference(sym) ||
          sym.isPackageObject

      if generalExclude then false
      else
        this match
          case Type(_, _) if sym.isType => true
          case Type(_, _) if sym.isTerm =>
            /* Type might be referenced by a path over an object:
             * ```
             * object sample:
             *    class X
             * val a: samp@@le.X = ???
             * ```
             * ignore objects that has companion class
             *
             * Also ignore objects that doesn't have any members.
             * By some reason traits might have a fake companion object(example: scala.sys.process.ProcessBuilderImpl)
             */
            val allowModule =
              sym.is(Module) &&
                (sym.companionClass == NoSymbol && sym.info.allMembers.nonEmpty)
            allowModule
          case Term if sym.isTerm || sym.is(Package) => true
          case Import => true
          case _ => false
      end if
    end include

    def allowBracketSuffix: Boolean =
      this match
        case Type(hasTypeParams, _) => !hasTypeParams
        case _ => false

    def allowTemplateSuffix: Boolean =
      this match
        case Type(_, hasNewKw) => hasNewKw
        case _ => false

  end CursorPos

  private lazy val cursorPos =
    calculateTypeInstanceAndNewPositions(path)

  private def calculateTypeInstanceAndNewPositions(
      path: List[Tree]
  ): CursorPos =
    path match
      case (_: Import) :: _ => CursorPos.Import
      case _ :: (_: Import) :: _ => CursorPos.Import
      case (head: (Select | Ident)) :: tail =>
        // https://github.com/lampepfl/dotty/issues/15750
        // due to this issue in dotty, because of which trees after typer lose information,
        // we have to calculate hasNoSquareBracket manually:
        val hasSquareBracket =
          val span: Span = head.srcPos.span
          if span.exists then
            var i = span.end
            while i < (text.length() - 1) && text(i).isWhitespace do i = i + 1

            if (i < text.length()) then text(i) == '['
            else false
          else false

        def typePos = CursorPos.Type(hasSquareBracket, hasNewKw = false)
        def newTypePos =
          CursorPos.Type(hasSquareBracket, hasNewKw = true)

        tail match
          case (v: ValOrDefDef) :: _ if v.tpt.sourcePos.contains(pos) =>
            typePos
          case New(selectOrIdent: (Select | Ident)) :: _
              if selectOrIdent.sourcePos.contains(pos) =>
            newTypePos
          case (a @ AppliedTypeTree(_, args)) :: _
              if args.exists(_.sourcePos.contains(pos)) =>
            typePos
          case (Template(constr, parents, self, _)) :: _
              if (constr :: self :: parents).exists(
                _.sourcePos.contains(pos)
              ) =>
            typePos
          case _ =>
            CursorPos.Term
        end match

      case (_: TypeTree) :: TypeApply(Select(newQualifier: New, _), _) :: _
          if newQualifier.sourcePos.contains(pos) =>
        CursorPos.Type(hasTypeParams = false, hasNewKw = true)

      case _ => CursorPos.Term
    end match
  end calculateTypeInstanceAndNewPositions

  def completions(): (List[CompletionValue], SymbolSearch.Result) =
    val (advanced, exclusive) = advancedCompletions(path, pos, completionPos)
    val (all, result) =
      if exclusive then (advanced, SymbolSearch.Result.COMPLETE)
      else
        path match
          // should not show completions for toplevel
          case Nil if pos.source.file.extension != "sc" =>
            (advanced, SymbolSearch.Result.COMPLETE)
          case Select(qual, _) :: _ if qual.tpe.isErroneous =>
            (advanced, SymbolSearch.Result.COMPLETE)
          case Select(qual, _) :: _ =>
            val (_, compilerCompletions) = Completion.completions(pos)
            val (compiler, result) = compilerCompletions
              .flatMap(toCompletionValues)
              .filterInteresting(qual.typeOpt.widenDealias)
            (advanced ++ compiler, result)
          case _ =>
            val (_, compilerCompletions) = Completion.completions(pos)
            val (compiler, result) = compilerCompletions
              .flatMap(toCompletionValues)
              .filterInteresting()
            (advanced ++ compiler, result)
        end match

    val application = CompletionApplication.fromPath(path)
    val ordering = completionOrdering(application)
    val values = application.postProcess(all.sorted(ordering))
    (values, result)
  end completions

  private def toCompletionValues(
      completion: Completion
  ): List[CompletionValue] =
    completion.symbols.flatMap(
      completionsWithSuffix(
        _,
        completion.label,
        CompletionValue.Compiler(_, _, _),
      )
    )
  end toCompletionValues

  inline private def undoBacktick(label: String): String =
    label.stripPrefix("`").stripSuffix("`")

  private def getParams(symbol: Symbol) =
    lazy val extensionParam = symbol.extensionParam
    if symbol.is(Flags.Extension) then
      symbol.paramSymss.filterNot(
        _.contains(extensionParam)
      )
    else symbol.paramSymss

  private def isAbstractType(symbol: Symbol) =
    (symbol.info.typeSymbol.is(Trait) // trait A{ def doSomething: Int}
    // object B{ new A@@}
    // Note: I realised that the value of Flag.Trait is flaky and
    // leads to the failure of one of the DocSuite tests
      || symbol.info.typeSymbol.isAllOf(
        Flags.JavaInterface // in Java:  interface A {}
        // in Scala 3: object B { new A@@}
      ) || symbol.info.typeSymbol.isAllOf(
        Flags.PureInterface // in Java: abstract class Shape { abstract void draw();}
        // Shape has only abstract members, so can be represented by a Java interface
        // in Scala 3: object B{ new Shap@@ }
      ) || (symbol.info.typeSymbol.is(Flags.Abstract) &&
        symbol.isClass) // so as to exclude abstract methods
    // abstract class A(i: Int){ def doSomething: Int}
    // object B{ new A@@}
    )
  end isAbstractType

  private def findSuffix(symbol: Symbol): Option[String] =
    val bracketSuffix =
      if shouldAddSnippet &&
        cursorPos.allowBracketSuffix && symbol.info.typeParams.nonEmpty
      then "[$0]"
      else ""

    val bracesSuffix =
      if shouldAddSnippet && symbol.is(Flags.Method)
      then
        val paramss = getParams(symbol)
        paramss match
          case Nil => ""
          case List(Nil) => "()"
          case _ if config.isCompletionSnippetsEnabled =>
            val onlyParameterless = paramss.forall(_.isEmpty)
            lazy val onlyImplicitOrTypeParams = paramss.forall(
              _.exists { sym =>
                sym.isType || sym.is(Implicit) || sym.is(Given)
              }
            )
            if onlyParameterless then "()" * paramss.length
            else if onlyImplicitOrTypeParams then ""
            else if bracketSuffix == "[$0]" then "()"
            else "($0)"
          case _ => ""
        end match
      else ""

    val templateSuffix =
      if shouldAddSnippet && cursorPos.allowTemplateSuffix
        && isAbstractType(symbol)
      then
        if bracketSuffix.nonEmpty || bracesSuffix.contains("$0") then " {}"
        else " {$0}"
      else ""

    val concludedSuffix = bracketSuffix + bracesSuffix + templateSuffix
    if concludedSuffix.nonEmpty then Some(concludedSuffix) else None

  end findSuffix

  def completionsWithSuffix(
      sym: Symbol,
      label: String,
      toCompletionValue: (String, Symbol, Option[String]) => CompletionValue,
  ): List[CompletionValue] =
    // workaround for earlier versions that force correctly detecting Java flags
    def isJavaDefined = if canDetectJavaObjectsCorrectly then
      sym.is(Flags.JavaDefined)
    else
      sym.info
      sym.is(Flags.JavaDefined)

    // find the apply completion that would need a snippet
    val methodSymbols =
      if shouldAddSnippet && sym.is(Flags.Module) && !isJavaDefined
      then
        val applSymbols = sym.info.member(nme.apply).allSymbols
        sym :: applSymbols
      else List(sym)

    methodSymbols.map { methodSymbol =>
      val suffix = findSuffix(methodSymbol)
      val name = undoBacktick(label)
      toCompletionValue(
        name,
        methodSymbol,
        suffix,
      )
    }
  end completionsWithSuffix

  /**
   * @return Tuple of completionValues and flag. If the latter boolean value is true
   *         Metals should provide advanced completions only.
   */
  private def advancedCompletions(
      path: List[Tree],
      pos: SourcePosition,
      completionPos: CompletionPos,
  ): (List[CompletionValue], Boolean) =
    lazy val rawPath = Paths
      .get(pos.source.path)
    lazy val rawFileName = rawPath
      .getFileName()
      .toString()
    lazy val filename = rawFileName
      .stripSuffix(".scala")

    path match
      case _ if ScaladocCompletions.isScaladocCompletion(pos, text) =>
        val values = ScaladocCompletions.contribute(pos, text, config)
        (values, true)

      case MatchExtractor(selector) =>
        (
          CaseKeywordCompletion.matchContribute(
            selector,
            completionPos,
            indexedContext,
            config,
          ),
          false,
        )

      case CaseExtractors.CaseExtractor(selector, parent) =>
        (
          CaseKeywordCompletion.contribute(
            selector,
            completionPos,
            indexedContext,
            config,
            parent,
          ),
          true,
        )

      case CaseExtractors.TypedCasePatternExtractor(
            selector,
            parent,
            identName,
          ) =>
        (
          CaseKeywordCompletion.contribute(
            selector,
            completionPos,
            indexedContext,
            config,
            parent,
            Some(identName),
            true,
          ),
          false,
        )

      case CaseExtractors.CasePatternExtractor(selector, parent, identName) =>
        (
          CaseKeywordCompletion.contribute(
            selector,
            completionPos,
            indexedContext,
            config,
            parent,
            Some(identName),
          ),
          false,
        )

      // in `case @@` we have to change completionPos to `case` pos,
      // otherwise after accepting completion we would get `case case None =>`
      case (lt @ Literal(
            Constant(null)
          )) :: (c: CaseDef) :: (m: Match) :: parent :: _ =>
        advancedCompletions(
          path.tail,
          c.startPos,
          CompletionPos.infer(c.startPos, text, path.tail),
        )

      // class FooImpl extends Foo:
      //   def x|
      case (dd: (DefDef | ValDef)) :: (t: Template) :: (td: TypeDef) :: _
          if t.parents.nonEmpty =>
        val completing =
          if dd.symbol.name == StdNames.nme.ERROR then None else Some(dd.symbol)
        val values = OverrideCompletions.contribute(
          td,
          completing,
          dd.sourcePos.start,
          indexedContext,
          search,
          config,
        )
        (values, true)

      // class FooImpl extends Foo:
      //   ov|
      case (ident: Ident) :: (t: Template) :: (td: TypeDef) :: _
          if t.parents.nonEmpty && ident.name.startsWith("o") =>
        val values = OverrideCompletions.contribute(
          td,
          None,
          ident.sourcePos.start,
          indexedContext,
          search,
          config,
        )
        // include compiler-oriented completions, in case `ov` doesn't mean the prefix of `override`
        (values, false)

      // class Main extends Val:
      //    def @@
      case (t: Template) :: (td: TypeDef) :: _ if t.parents.nonEmpty =>
        val values = OverrideCompletions.contribute(
          td,
          None,
          t.sourcePos.start,
          indexedContext,
          search,
          config,
        )
        (values, true)

      // class Main extends Val:
      //    hello@@
      case (sel: Select) :: (t: Template) :: (td: TypeDef) :: _
          if t.parents.nonEmpty =>
        val values = OverrideCompletions.contribute(
          td,
          Some(sel.symbol),
          sel.sourcePos.start,
          indexedContext,
          search,
          config,
        )
        (values, false)

      // class Fo@@
      case (td: TypeDef) :: _
          if Fuzzy.matches(td.symbol.name.decoded, filename) =>
        val values = FilenameCompletions.contribute(filename, td)
        (values, true)
      case (lit @ Literal(Constant(_: String))) :: _ =>
        val completions = InterpolatorCompletions
          .contribute(
            pos,
            completionPos,
            indexedContext,
            lit,
            path,
            this,
            config.isCompletionSnippetsEnabled(),
            search,
            config,
            buildTargetIdentifier,
          )
          .filterInteresting(enrich = false)
          ._1
        (completions, true)

      case (imp @ Import(expr, selectors)) :: _
          if isAmmoniteFileCompletionPosition(imp, rawFileName) =>
        (
          AmmoniteFileCompletions.contribute(
            expr,
            selectors,
            pos.endPos.toLsp,
            rawPath.toString(),
            workspace,
            rawFileName,
          ),
          true,
        )

      // From Scala 3.1.3-RC3 (as far as I know), path contains
      // `Literal(Constant(null))` on head for an incomplete program, in this case, just ignore the head.
      case Literal(Constant(null)) :: tl =>
        advancedCompletions(tl, pos, completionPos)

      case _ =>
        val args = NamedArgCompletions.contribute(
          pos,
          path,
          indexedContext,
          config.isCompletionSnippetsEnabled,
        )
        val keywords = KeywordsCompletions.contribute(path, completionPos)
        (args ++ keywords, false)
    end match
  end advancedCompletions

  private def isAmmoniteFileCompletionPosition(
      tree: Tree,
      fileName: String,
  ): Boolean =

    def getQualifierStart(identOrSelect: Tree): String =
      identOrSelect match
        case Ident(name) => name.toString
        case Select(newQual, name) => getQualifierStart(newQual)
        case _ => ""

    tree match
      case Import(identOrSelect, _) =>
        fileName.isAmmoniteGeneratedFile && getQualifierStart(identOrSelect)
          .toString()
          .startsWith("$file")
      case _ => false
  end isAmmoniteFileCompletionPosition

  private def description(sym: Symbol): String =
    if sym.isType then sym.showFullName
    else sym.info.widenTermRefExpr.show

  private def enrichWithSymbolSearch(
      visit: CompletionValue => Boolean,
      qualType: Type = ctx.definitions.AnyType,
  ): Option[SymbolSearch.Result] =
    val query = completionPos.query
    completionPos.kind match
      case CompletionKind.Empty =>
        val filtered = indexedContext.scopeSymbols
          .filter(sym =>
            !sym.isConstructor && (!sym.is(Synthetic) || sym.is(Module))
          )

        filtered.map { sym =>
          visit(CompletionValue.scope(sym.decodedName, sym))
        }
        Some(SymbolSearch.Result.INCOMPLETE)
      case CompletionKind.Scope =>
        val visitor = new CompilerSearchVisitor(
          query,
          sym =>
            indexedContext.lookupSym(sym) match
              case IndexedContext.Result.InScope =>
                visit(CompletionValue.scope(sym.decodedName, sym))
              case _ =>
                completionsWithSuffix(
                  sym,
                  sym.decodedName,
                  CompletionValue.Workspace(_, _, _),
                ).forall(visit),
        )
        Some(search.search(query, buildTargetIdentifier, visitor))
      case CompletionKind.Members if query.nonEmpty =>
        val visitor = new CompilerSearchVisitor(
          query,
          sym =>
            if sym.is(ExtensionMethod) &&
              qualType.widenDealias <:< sym.extensionParam.info.widenDealias
            then
              completionsWithSuffix(
                sym,
                sym.decodedName,
                CompletionValue.Extension(_, _, _),
              ).forall(visit)
            else false,
        )
        Some(search.searchMethods(query, buildTargetIdentifier, visitor))
      case CompletionKind.Members => // query.isEmpry
        Some(SymbolSearch.Result.INCOMPLETE)
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
    def filterInteresting(
        qualType: Type = ctx.definitions.AnyType,
        enrich: Boolean = true,
    ): (List[CompletionValue], SymbolSearch.Result) =

      val isSeen = mutable.Set.empty[String]
      val buf = List.newBuilder[CompletionValue]
      def visit(head: CompletionValue): Boolean =
        val (id, include) =
          head match
            case doc: CompletionValue.Document => (doc.label, true)
            case over: CompletionValue.Override => (over.label, true)
            case ck: CompletionValue.CaseKeyword => (ck.label, true)
            case symOnly: CompletionValue.Symbolic =>
              val sym = symOnly.symbol
              val name = SemanticdbSymbols.symbolName(sym)
              val id =
                if sym.isClass || sym.is(Module) then
                  // drop #|. at the end to avoid duplication
                  name.substring(0, name.length - 1)
                else name
              val include = cursorPos.include(sym)
              (id, include)
            case kw: CompletionValue.Keyword => (kw.label, true)
            case mc: CompletionValue.MatchCompletion => (mc.label, true)
            case namedArg: CompletionValue.NamedArg =>
              val id = namedArg.label + "="
              (id, true)
            case autofill: CompletionValue.Autofill =>
              (autofill.label, true)
            case fileSysMember: CompletionValue.FileSystemMember =>
              (fileSysMember.label, true)

        if !isSeen(id) && include then
          isSeen += id
          buf += head
          true
        else false
      end visit

      l.foreach(visit)

      if enrich then
        val searchResult =
          enrichWithSymbolSearch(visit, qualType).getOrElse(
            SymbolSearch.Result.COMPLETE
          )
        (buf.result, searchResult)
      else (buf.result, SymbolSearch.Result.COMPLETE)

    end filterInteresting
  end extension

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
    defn.ScalaPredefModule.requiredMethod(nme.valueOf),
  ).flatMap(_.alternatives.map(_.symbol)).toSet

  private def isNotLocalForwardReference(sym: Symbol)(using Context): Boolean =
    !sym.isLocalToBlock ||
      !sym.srcPos.isAfter(pos) ||
      sym.is(Param)

  private def computeRelevancePenalty(
      completion: CompletionValue,
      application: CompletionApplication,
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

      // fields are more relevant than non fields (such as method)
      completion match
        // For override-completion, we don't care fields or methods because
        // we can override both fields and non-fields
        case _: CompletionValue.Override =>
          relevance |= IsNotGetter
        case _ if !hasGetter(sym) =>
          relevance |= IsNotGetter
        case _ =>

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
      case ov: CompletionValue.Override =>
        var penalty = symbolRelevance(ov.symbol)
        // show the abstract members first
        if !ov.symbol.is(Deferred) then penalty |= MemberOrdering.IsNotAbstract
        penalty
      case CompletionValue.Workspace(_, sym, _) =>
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
    nme.finalize_,
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
            case CompletionValue.Compiler(label, sym, suffix)
                if sym.info.paramNamess.nonEmpty && isMember(sym) =>
              CompletionValue.Compiler(
                label,
                substituteTypeVars(sym),
                suffix,
              )
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
      val fuzzyCache = mutable.Map.empty[CompletionValue, Int]

      def compareLocalSymbols(s1: Symbol, s2: Symbol): Int =
        if s1.isLocal && s2.isLocal then
          val firstIsAfter = s1.srcPos.isAfter(s2.srcPos)
          if firstIsAfter then -1 else 1
        else 0
      end compareLocalSymbols

      def compareByRelevance(o1: CompletionValue, o2: CompletionValue): Int =
        Integer.compare(
          computeRelevancePenalty(o1, application),
          computeRelevancePenalty(o2, application),
        )

      def fuzzyScore(o: CompletionValue.Symbolic): Int =
        fuzzyCache.getOrElseUpdate(
          o, {
            val name = o.label.toLowerCase()
            if name.startsWith(queryLower) then 0
            else if name.toLowerCase().contains(queryLower) then 1
            else 2
          },
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
                sym1: CompletionValue.CaseKeyword,
                sym2: CompletionValue.Compiler,
              ) =>
            0
          case (
                sym1: CompletionValue.Compiler,
                sym2: CompletionValue.CaseKeyword,
              ) =>
            1
          case (
                sym1: CompletionValue.Symbolic,
                sym2: CompletionValue.Symbolic,
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
                  fuzzyScore(sym1),
                  fuzzyScore(sym2),
                )
                if byFuzzy != 0 then byFuzzy
                else
                  val byIdentifier = IdentifierComparator.compare(
                    s1.name.show,
                    s2.name.show,
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
                        s2.paramSymss.flatten.size,
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

  object MatchExtractor:
    def unapply(path: List[Tree]) =
      path match
        // foo mat@@
        case (sel @ Select(qualifier, name)) :: _
            if "match".startsWith(name.toString()) && text.charAt(
              completionPos.start - 1
            ) == ' ' =>
          Some(qualifier)
        // foo match @@
        case (c: CaseDef) :: (m: Match) :: _
            if completionPos.query.startsWith("match") =>
          Some(m.selector)
        // foo ma@tch (no cases)
        case (m @ Match(
              _,
              CaseDef(Literal(Constant(null)), _, _) :: Nil,
            )) :: _ =>
          Some(m.selector)
        case _ => None
  end MatchExtractor

end Completions
