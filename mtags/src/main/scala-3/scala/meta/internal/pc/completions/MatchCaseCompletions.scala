package scala.meta.internal.pc
package completions

import java.{util as ju}

import scala.collection.JavaConverters.*
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.internal.pc.AutoImports.AutoImport
import scala.meta.internal.pc.AutoImports.AutoImportsGenerator
import scala.meta.internal.pc.IndexedContext.Result
import scala.meta.internal.pc.MetalsInteractive.*
import scala.meta.internal.pc.printer.ShortenedNames
import scala.meta.pc.PresentationCompilerConfig

import dotty.tools.dotc.ast.tpd.*
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
   * @param patternOnly `true` if we want completions without `case` keyword
   * @param hasBind `true` when `case _: @@ =>`, if hasBind we don't need unapply completions
   */
  def contribute(
      selector: Tree,
      completionPos: CompletionPos,
      indexedContext: IndexedContext,
      config: PresentationCompilerConfig,
      parent: Tree,
      patternOnly: Boolean = false,
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

    val result = ListBuffer.empty[CompletionValue]
    val isVisited = mutable.Set.empty[Symbol]

    def visit(sym: Symbol, name: String, autoImports: List[l.TextEdit]): Unit =
      val isValid = !isVisited(sym) && !parents.isParent(sym)
        && (sym.is(Case) || sym.is(Flags.Module) || sym.isClass)
        && parents.isSubClass(sym, false)
      def recordVisit(s: Symbol): Unit =
        if s != NoSymbol && !isVisited(s) then
          isVisited += s
          recordVisit(s.moduleClass)
          recordVisit(s.sourceModule)
      if isValid then
        recordVisit(sym)
        result += completionGenerator.toCompletionValue(
          sym,
          name,
          autoImports,
        )
      end if
    end visit
    val selectorSym = parents.selector.typeSymbol

    // Special handle case when selector is a tuple or `FunctionN`.
    if definitions.isTupleClass(selectorSym) || definitions.isFunctionClass(
        selectorSym
      )
    then
      val label =
        if !patternOnly then s"case ${parents.selector.show} =>"
        else parents.selector.show
      result += CompletionValue.CaseKeyword(
        selectorSym,
        label,
        Some(
          if !patternOnly then
            if config.isCompletionSnippetsEnabled() then "case ($0) =>"
            else "case () =>"
          else if config.isCompletionSnippetsEnabled() then "($0)"
          else "()"
        ),
        Nil,
        range = Some(completionPos.toEditRange),
        command = config.parameterHintsCommand().asScala,
      )
    else
      // Step 1: walk through scope members.
      indexedContext.scopeSymbols
        .foreach(s => visit(s.info.dealias.typeSymbol, s.decodedName, Nil))

      // Step 2: walk through known subclasses of sealed types.
      selectorSym.sealedStrictDescendants.foreach { sym =>
        if !(sym.is(Sealed) && (sym.is(Abstract) || sym.is(Trait))) then
          val autoImport = autoImportsGen.forSymbol(sym)
          autoImport match
            case Some(value) =>
              visit(sym.info.dealias.typeSymbol, sym.decodedName, value)
            case scala.None =>
              visit(sym.info.dealias.typeSymbol, sym.showFullName, Nil)
        else ()
      }
    end if

    val res = result.result()
    res
  end contribute

  /**
   * A `match` keyword completion to generate an exhaustive pattern match for sealed types.
   *
   * @param selector the match expression being deconstructed or `EmptyTree` when
   *                 not in a match expression (for example `List(1).foreach { case@@ }`.
   * @param completionPos the position of the completion
   * @param typedtree typed tree of the file, used for generating auto imports
   * @param indexedContext
   * @param config
   */
  def matchContribute(
      selector: Tree,
      completionPos: CompletionPos,
      indexedContext: IndexedContext,
      config: PresentationCompilerConfig,
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
      val subclasses = ListBuffer.empty[Symbol]
      val parents = ListBuffer.empty[Symbol]
      // Get parent types from refined type
      def loop(tpe: Type): Unit =
        tpe match
          case AndType(tp1, tp2) => // for refined type like A with B with C
            loop(tp1)
            loop(tp2)
          case t => parents += tpe.typeSymbol
      loop(tpe.widen.bounds.hi)
      parents.toList.foreach { parent =>
        // There is an issue in Dotty, `sealedStrictDescendants` ends in an exception for java enums. https://github.com/lampepfl/dotty/issues/15908
        val descendants =
          if parent.isAllOf(JavaEnumTrait) then parent.children
          else parent.sealedStrictDescendants
        descendants.foreach(sym =>
          if !(sym.is(Sealed) && (sym.is(Abstract) || sym.is(Trait))) then
            subclasses += sym
        )
      }
      val subclassesResult = subclasses.toList
      subclassesResult
    end subclassesForType

    val sortedSubclasses = subclassesForType(tpe)
    sortedSubclasses.foreach { case sym =>
      val autoImport = autoImportsGen.forSymbol(sym)
      autoImport match
        case Some(value) =>
          result += completionGenerator.toCompletionValue(
            sym.info.dealias.typeSymbol,
            sym.decodedName,
            value,
          )
        case None =>
          result += completionGenerator.toCompletionValue(
            sym.info.dealias.typeSymbol,
            sym.decodedName,
            Nil,
          )
    }

    val basicMatch = CompletionValue.MatchCompletion(
      "match",
      Some(
        if clientSupportsSnippets then "match\n\tcase$0\n"
        else "match"
      ),
      Nil,
      "",
    )
    val members = result.result()
    val completions = members match
      case Nil => List(basicMatch)
      case head :: tail =>
        val insertText = Some(
          tail
            .map(_.label)
            .mkString(
              if clientSupportsSnippets then s"match\n\t${head.label} $$0\n\t"
              else s"match\n\t${head.label}\n\t",
              "\n\t",
              "\n",
            )
        )
        val exhaustive = CompletionValue.MatchCompletion(
          "match (exhaustive)",
          insertText,
          members.flatMap(_.additionalEdits),
          s" ${tpe.typeSymbol.decodedName} (${members.length} cases)",
        )
        List(basicMatch, exhaustive)
    completions
  end matchContribute

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
    patternOnly: Boolean = false,
    hasBind: Boolean = false,
):
  def toCompletionValue(
      sym: Symbol,
      name: String,
      autoImports: List[l.TextEdit],
  )(using Context): CompletionValue.CaseKeyword =
    sym.info
    val isModuleLike =
      sym.is(Flags.Module) || sym.isOneOf(JavaEnumTrait) || sym.isOneOf(
        JavaEnumValue
      )
    val pattern =
      if (sym.is(Case) || isModuleLike) && !hasBind then
        val isInfixEligible =
          indexedContext.lookupSym(sym) == Result.InScope
            || autoImports.nonEmpty
        if isInfixEligible && sym.is(Case) && !Character
            .isUnicodeIdentifierStart(
              sym.decodedName.head
            )
        then
          // Deconstructing the symbol as an infix operator, for example `case head :: tail =>`
          tryInfixPattern(sym).getOrElse(
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
          hasBind,
        ) // Symbol is not a case class with unapply deconstructor so we use typed pattern, example `_: User`

    val label =
      if !patternOnly then s"case $pattern =>"
      else pattern
    CompletionValue.CaseKeyword(
      sym,
      label,
      Some(label + (if clientSupportsSnippets then " $0" else "")),
      autoImports,
      // filterText = if !doFilterText then Some("") else None,
      range = Some(completionPos.toEditRange),
    )
  end toCompletionValue

  private def tryInfixPattern(sym: Symbol)(using Context): Option[String] =
    sym.primaryConstructor.paramSymss match
      case (a :: b :: Nil) :: Nil =>
        Some(
          s"${a.decodedName} ${sym.decodedName} ${b.decodedName}"
        )
      case _ :: (a :: b :: Nil) :: _ =>
        Some(
          s"${a.decodedName} ${sym.decodedName} ${b.decodedName}"
        )
      case _ => None

  private def unapplyPattern(
      sym: Symbol,
      name: String,
      isModuleLike: Boolean,
  )(using Context): String =
    val suffix =
      if isModuleLike then ""
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
      hasBind: Boolean = false,
  )(using Context): String =
    val suffix = sym.typeParams match
      case Nil => ""
      case tparams => tparams.map(_ => "_").mkString("[", ", ", "]")
    val bind = if hasBind then "" else "_: "
    bind + name + suffix
end CompletionValueGenerator
