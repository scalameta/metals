package scala.meta.internal.pc
package completions

import java.net.URI
import java.{util as ju}

import scala.collection.JavaConverters.*
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.internal.pc.AutoImports.AutoImport
import scala.meta.internal.pc.AutoImports.AutoImportsGenerator
import scala.meta.internal.pc.AutoImports.SymbolImport
import scala.meta.internal.pc.IndexedContext.Result
import scala.meta.internal.pc.MetalsInteractive.*
import scala.meta.internal.pc.printer.ShortenedNames
import scala.meta.pc.PresentationCompilerConfig
import scala.meta.pc.SymbolSearch

import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Definitions
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.Flags.*
import dotty.tools.dotc.core.Names.Name
import dotty.tools.dotc.core.Symbols.NoSymbol
import dotty.tools.dotc.core.Symbols.Symbol
import dotty.tools.dotc.core.Types.AndType
import dotty.tools.dotc.core.Types.NoType
import dotty.tools.dotc.core.Types.Type
import dotty.tools.dotc.core.Types.TypeRef
import dotty.tools.dotc.interactive.Interactive
import dotty.tools.dotc.interactive.InteractiveDriver
import dotty.tools.dotc.util.SourcePosition
import org.eclipse.{lsp4j as l}

object CaseKeywordCompletion:

  /**
   * A `case` completion showing the valid subtypes of the type being deconstructed.
   *
   * @param selector `selector` of `selector match { cases }`  or `EmptyTree` when
   *                 not in a match expression (for example `List(1).foreach { case@@ }`.
   * @param completionPos the position of the completion
   * @param typedtree typed tree of the file, used for generating auto imports
   * @param indexedContext
   * @param config
   * @param parent the parent tree node of the pattern match, for example `Apply(_, _)` when in
   *               `List(1).foreach { cas@@ }`, used as fallback to compute the type of the selector when
   *               it's `EmptyTree`.
   * @param patternOnly `None` for `case@@`, `Some(query)` for `case query@@ =>` or `case ab: query@@ =>`
   * @param hasBind `true` when `case _: @@ =>`, if hasBind we don't need unapply completions
   */
  def contribute(
      selector: Tree,
      completionPos: CompletionPos,
      indexedContext: IndexedContext,
      config: PresentationCompilerConfig,
      search: SymbolSearch,
      parent: Tree,
      patternOnly: Option[String] = None,
      hasBind: Boolean = false,
  ): List[CompletionValue] =
    import indexedContext.ctx
    val pos = completionPos.sourcePos
    val typedTree = indexedContext.ctx.compilationUnit.tpdTree
    val definitions = indexedContext.ctx.definitions
    val text = pos.source.content().mkString
    val clientSupportsSnippets = config.isCompletionSnippetsEnabled()
    lazy val autoImportsGen = AutoImports.generator(
      pos,
      text,
      typedTree,
      indexedContext,
      config,
    )
    val completionGenerator = CompletionValueGenerator(
      indexedContext,
      completionPos,
      clientSupportsSnippets,
      patternOnly,
      hasBind,
    )
    val parents: Parents = selector match
      case EmptyTree =>
        val seenFromType = parent match
          case TreeApply(fun, _) if fun.tpe != null && !fun.tpe.isErroneous =>
            fun.tpe
          case _ =>
            parent.tpe
        seenFromType.paramInfoss match
          case (head :: Nil) :: _
              if definitions.isFunctionType(head) || head.isRef(
                definitions.PartialFunctionClass
              ) =>
            val dealiased = head.widenDealias
            val argTypes =
              head.argTypes.init
            new Parents(argTypes, definitions)
          case _ =>
            new Parents(NoType, definitions)
      case sel => new Parents(sel.tpe, definitions)

    val selectorSym = parents.selector.typeSymbol

    // Special handle case when selector is a tuple or `FunctionN`.
    if definitions.isTupleClass(selectorSym) || definitions.isFunctionClass(
        selectorSym
      )
    then
      val label =
        if patternOnly.isEmpty then s"case ${parents.selector.show} =>"
        else parents.selector.show
      List(
        CompletionValue.CaseKeyword(
          selectorSym,
          label,
          Some(
            if patternOnly.isEmpty then
              if config.isCompletionSnippetsEnabled() then "case ($0) =>"
              else "case () =>"
            else if config.isCompletionSnippetsEnabled() then "($0)"
            else "()"
          ),
          Nil,
          range = Some(completionPos.toEditRange),
          command = config.parameterHintsCommand().asScala,
        )
      )
    else
      val result = ListBuffer.empty[SymbolImport]
      val isVisited = mutable.Set.empty[Symbol]
      def visit(symImport: SymbolImport): Unit =

        def recordVisit(s: Symbol): Unit =
          if s != NoSymbol && !isVisited(s) then
            isVisited += s
            recordVisit(s.moduleClass)
            recordVisit(s.sourceModule)

        val sym = symImport.sym
        if !isVisited(sym) then
          recordVisit(sym)
          if completionGenerator.fuzzyMatches(symImport.name) then
            result += symImport
      end visit

      // Step 0: case for selector type
      selectorSym.info match
        case NoType => ()
        case _ =>
          if !(selectorSym.is(Sealed) &&
              (selectorSym.is(Abstract) || selectorSym.is(Trait)))
          then visit((autoImportsGen.inferSymbolImport(selectorSym)))

      // Step 1: walk through scope members.
      def isValid(sym: Symbol) = !parents.isParent(sym)
        && (sym.is(Case) || sym.is(Flags.Module) || sym.isClass)
        && parents.isSubClass(sym, false)
        && (sym.isPublic || sym.isAccessibleFrom(selectorSym.info))
      indexedContext.scopeSymbols
        .foreach(s =>
          val ts = s.info.dealias.typeSymbol
          if (isValid(ts)) then visit(autoImportsGen.inferSymbolImport(ts))
        )
      // Step 2: walk through known subclasses of sealed types.
      val sealedDescs = MetalsSealedDesc.sealedStrictDescendants(selectorSym)
      sealedDescs.foreach { sym =>
        val symbolImport = autoImportsGen.inferSymbolImport(sym)
        visit(symbolImport)
      }
      val res = result.result()

      val members =
        res.flatMap { symImport =>
          completionGenerator
            .labelForCaseMember(symImport.sym, symImport.name)
            .map(label => (symImport.sym, label, symImport.importSel))

        }

      val caseItems = members.map((sym, label, importSel) =>
        completionGenerator.toCompletionValue(
          sym,
          label,
          autoImportsGen.renderImports(importSel.toList),
        )
      )

      selector match
        // In `List(foo).map { cas@@} we want to provide also `case (exhaustive)` completion
        // which works like exhaustive match.
        case EmptyTree =>
          val sealedMembers =
            members.filter((sym, _, _) => sealedDescs.contains(sym))
          sealedMembers match
            case Nil => caseItems
            case (_, firstLabel, _) :: tail =>
              val insertText = Some(
                tail
                  .map((_, label, _) => label)
                  .mkString(
                    if clientSupportsSnippets then s"\n\t${firstLabel} $$0\n\t"
                    else s"\n\t${firstLabel}\n\t",
                    "\n\t",
                    "\n",
                  )
              )
              val allImports =
                sealedMembers.flatMap((_, _, importSel) => importSel).distinct
              val importEdit = autoImportsGen.renderImports(allImports)
              val exhaustive = CompletionValue.MatchCompletion(
                s"case (exhaustive)",
                insertText,
                importEdit.toList,
                s" ${selectorSym.decodedName} (${res.length} cases)",
              )
              exhaustive :: caseItems
          end match
        case _ => caseItems
      end match
    end if

  end contribute

  /**
   * A `match` keyword completion to generate an exhaustive pattern match for sealed types.
   *
   * @param selector the match expression being deconstructed or `EmptyTree` when
   *                 not in a match expression (for example `List(1).foreach { case@@ }`.
   * @param completionPos the position of the completion
   * @param typedtree typed tree of the file, used for generating auto imports
   */
  def matchContribute(
      selector: Tree,
      completionPos: CompletionPos,
      indexedContext: IndexedContext,
      config: PresentationCompilerConfig,
      search: SymbolSearch,
  ): List[CompletionValue] =
    import indexedContext.ctx
    val pos = completionPos.sourcePos
    val typedTree = indexedContext.ctx.compilationUnit.tpdTree
    val definitions = indexedContext.ctx.definitions
    val text = pos.source.content().mkString
    val clientSupportsSnippets = config.isCompletionSnippetsEnabled()
    lazy val autoImportsGen = AutoImports.generator(
      pos,
      text,
      typedTree,
      indexedContext,
      config,
    )
    val completionGenerator = CompletionValueGenerator(
      indexedContext,
      completionPos,
      clientSupportsSnippets,
    )
    val result = ListBuffer.empty[CompletionValue]
    val tpe = selector.tpe.widen.bounds.hi match
      case tr @ TypeRef(_, _) => tr.underlying
      case t => t

    def subclassesForType(tpe: Type)(using Context): List[Symbol] =
      val parents = ListBuffer.empty[Symbol]
      // Get parent types from refined type
      def loop(tpe: Type): Unit =
        tpe match
          case AndType(tp1, tp2) => // for refined type like A with B with C
            loop(tp1)
            loop(tp2)
          case t => parents += tpe.typeSymbol
      loop(tpe.widen.bounds.hi)
      val subclasses = parents.toList.flatMap { parent =>
        // There is an issue in Dotty, `sealedStrictDescendants` ends in an exception for java enums. https://github.com/lampepfl/dotty/issues/15908
        if parent.isAllOf(JavaEnumTrait) then parent.children
        else MetalsSealedDesc.sealedStrictDescendants(parent)
      }
      sortSubclasses(tpe, subclasses, completionPos.sourceUri, search)
    end subclassesForType

    val sortedSubclasses = subclassesForType(tpe)
    val (labels, imports) =
      sortedSubclasses.flatMap { sym =>
        val enter = autoImportsGen.inferSymbolImport(sym)
        completionGenerator
          .labelForCaseMember(enter.sym, enter.name)
          .map(label => (label, enter.importSel))
      }.unzip

    val basicMatch = CompletionValue.MatchCompletion(
      "match",
      Some(
        if clientSupportsSnippets then "match\n\tcase$0\n"
        else "match"
      ),
      Nil,
      "",
    )
    val completions = labels match
      case Nil => List(basicMatch)
      case head :: tail =>
        val insertText = Some(
          tail
            .mkString(
              if clientSupportsSnippets then s"match\n\t${head} $$0\n\t"
              else s"match\n\t${head}\n\t",
              "\n\t",
              "\n",
            )
        )
        val importEdit = autoImportsGen.renderImports(imports.flatten.distinct)
        val exhaustive = CompletionValue.MatchCompletion(
          "match (exhaustive)",
          insertText,
          importEdit.toList,
          s" ${tpe.typeSymbol.decodedName} (${labels.length} cases)",
        )
        List(basicMatch, exhaustive)
    completions
  end matchContribute

  private def sortSubclasses(
      tpe: Type,
      syms: List[Symbol],
      uri: URI,
      search: SymbolSearch,
  )(using Context): List[Symbol] =
    if syms.forall(_.sourcePos.exists) then syms.sortBy(_.sourcePos.point)
    else
      val defnSymbols = search
        .definitionSourceToplevels(
          SemanticdbSymbols.symbolName(tpe.typeSymbol),
          uri,
        )
        .asScala
        .zipWithIndex
        .toMap
      syms.sortBy { sym =>
        val semancticName = SemanticdbSymbols.symbolName(sym)
        defnSymbols.getOrElse(semancticName, -1)
      }

end CaseKeywordCompletion

class Parents(val selector: Type, definitions: Definitions)(using Context):
  def this(tpes: List[Type], definitions: Definitions)(using Context) =
    this(
      tpes match
        case Nil => NoType
        case head :: Nil => head
        case _ => definitions.tupleType(tpes)
      ,
      definitions,
    )

  val isParent: Set[Symbol] =
    Set(selector.typeSymbol, selector.typeSymbol.companion)
      .filterNot(_ == NoSymbol)
  val isBottom: Set[Symbol] = Set[Symbol](
    definitions.NullClass,
    definitions.NothingClass,
  )
  def isSubClass(typeSymbol: Symbol, includeReverse: Boolean)(using
      Context
  ): Boolean =
    !isBottom(typeSymbol) &&
      isParent.exists { parent =>
        typeSymbol.isSubClass(parent) ||
        (includeReverse && parent.isSubClass(typeSymbol))
      }
end Parents

class CompletionValueGenerator(
    indexedContext: IndexedContext,
    completionPos: CompletionPos,
    clientSupportsSnippets: Boolean,
    patternOnly: Option[String] = None,
    hasBind: Boolean = false,
):
  def fuzzyMatches(name: String) =
    patternOnly match
      case None => true
      case Some("") => true
      case Some(query) => CompletionFuzzy.matches(query, name)

  def labelForCaseMember(sym: Symbol, name: String)(using
      Context
  ): Option[String] =
    val isModuleLike =
      sym.is(Flags.Module) || sym.isOneOf(JavaEnumTrait) || sym.isOneOf(
        JavaEnumValue
      ) || sym.isAllOf(EnumCase)
    if isModuleLike && hasBind then None
    else
      val pattern =
        if (sym.is(Case) || isModuleLike) && !hasBind then
          if sym.is(Case) &&
            sym.decodedName == name &&
            !Character.isUnicodeIdentifierStart(name.head)
          then
            // Deconstructing the symbol as an infix operator, for example `case head :: tail =>`
            tryInfixPattern(sym, name).getOrElse(
              unapplyPattern(sym, name, isModuleLike)
            )
          else
            unapplyPattern(
              sym,
              name,
              isModuleLike,
            ) // Apply syntax, example `case ::(head, tail) =>`
          end if
        else
          typePattern(
            sym,
            name,
          ) // Symbol is not a case class with unapply deconstructor so we use typed pattern, example `_: User`
        end if
      end pattern

      val out =
        if patternOnly.isEmpty then s"case $pattern =>"
        else pattern
      Some(out)
    end if
  end labelForCaseMember

  def toCompletionValue(
      sym: Symbol,
      label: String,
      autoImport: Option[l.TextEdit],
  )(using Context): CompletionValue.CaseKeyword =
    val cursorSuffix =
      (if patternOnly.nonEmpty then "" else " ") +
        (if clientSupportsSnippets then "$0" else "")
    CompletionValue.CaseKeyword(
      sym,
      label,
      Some(label + cursorSuffix),
      autoImport.toList,
      range = Some(completionPos.toEditRange),
    )
  end toCompletionValue

  private def tryInfixPattern(sym: Symbol, name: String)(using
      Context
  ): Option[String] =
    sym.primaryConstructor.paramSymss match
      case (a :: b :: Nil) :: Nil =>
        Some(
          s"${a.decodedName} $name ${b.decodedName}"
        )
      case _ :: (a :: b :: Nil) :: _ =>
        Some(
          s"${a.decodedName} $name ${b.decodedName}"
        )
      case _ => None

  private def unapplyPattern(
      sym: Symbol,
      name: String,
      isModuleLike: Boolean,
  )(using Context): String =
    val suffix =
      if isModuleLike && !(sym.isClass && sym.is(Enum)) then ""
      else
        sym.primaryConstructor.paramSymss match
          case Nil => "()"
          case tparams :: params :: _ =>
            params
              .map(param => param.showName)
              .mkString("(", ", ", ")")
          case head :: _ =>
            head
              .map(param => param.showName)
              .mkString("(", ", ", ")")
    name + suffix
  end unapplyPattern

  private def typePattern(
      sym: Symbol,
      name: String,
  )(using Context): String =
    val suffix = sym.typeParams match
      case Nil => ""
      case tparams => tparams.map(_ => "?").mkString("[", ", ", "]")
    val bind = if hasBind then "" else "_: "
    bind + name + suffix
end CompletionValueGenerator

class MatchCaseExtractor(
    pos: SourcePosition,
    text: String,
    completionPos: CompletionPos,
):
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
  object CaseExtractor:
    def unapply(path: List[Tree])(using Context): Option[(Tree, Tree)] =
      path match
        // foo match
        // case None => ()
        // ca@@
        case (id @ Ident(name)) :: Block(stats, expr) :: parent :: _
            if "case"
              .startsWith(name.toString()) && stats.lastOption.exists(
              _.isInstanceOf[Match]
            ) && expr == id =>
          val selector = stats.last.asInstanceOf[Match].selector
          Some((selector, parent))
        // List(Option(1)).collect {
        //   case Some(value) => ()
        //   ca@@
        // }
        case (ident @ Ident(name)) :: Block(
              _,
              expr,
            ) :: (cd: CaseDef) :: (m: Match) :: parent :: _
            if ident == expr && "case"
              .startsWith(name.toString()) &&
              cd.sourcePos.startLine != pos.startLine =>
          Some((m.selector, parent))
        // foo match
        //  ca@@
        case (_: CaseDef) :: (m: Match) :: parent :: _ =>
          Some((m.selector, parent))
        // List(foo).map { ca@@ }
        case (ident @ Ident(name)) :: Block(stats, expr) :: (appl @ Apply(
              fun,
              args,
            )) :: _
            if stats.isEmpty && ident == expr && "case".startsWith(
              name.toString()
            ) =>
          Some((EmptyTree, appl))

        case _ => None
  end CaseExtractor

  object CasePatternExtractor:
    def unapply(path: List[Tree])(using Context) =
      path match
        // case @@
        case (c @ CaseDef(
              Literal((Constant(null))),
              _,
              _,
            )) :: (m: Match) :: parent :: _
            if pos.start - c.sourcePos.start > 4 =>
          Some((m.selector, parent, ""))
        // case Som@@
        case Ident(name) :: CaseExtractor(selector, parent) =>
          Some((selector, parent, name.decoded))
        // case abc @ Som@@
        case Ident(name) :: Bind(_, _) :: CaseExtractor(selector, parent) =>
          Some((selector, parent, name.decoded))
        // case abc @ @@
        case Bind(_, _) :: CaseExtractor(selector, parent) =>
          Some((selector, parent, ""))
        case _ => None

  end CasePatternExtractor

  object TypedCasePatternExtractor:
    def unapply(path: List[Tree])(using Context) =
      path match
        // case _: Som@@ =>
        case Ident(name) :: Typed(_, _) :: CaseExtractor(selector, parent) =>
          Some((selector, parent, name.decoded))
        // case _: @@ =>
        case Typed(_, _) :: CaseExtractor(selector, parent) =>
          Some((selector, parent, ""))
        // case ab: @@ =>
        case Bind(_, Typed(_, _)) :: CaseExtractor(selector, parent) =>
          Some((selector, parent, ""))
        // case ab: Som@@ =>
        case Ident(name) :: Typed(_, _) :: Bind(_, _) :: CaseExtractor(
              selector,
              parent,
            ) =>
          Some((selector, parent, name.decoded))
        case _ => None
  end TypedCasePatternExtractor
end MatchCaseExtractor
