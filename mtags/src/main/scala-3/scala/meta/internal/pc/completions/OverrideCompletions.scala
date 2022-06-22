package scala.meta.internal.pc
package completions

import java.{util as ju}

import scala.collection.JavaConverters.*

import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.internal.pc.AutoImports.AutoImport
import scala.meta.internal.pc.AutoImports.AutoImportsGenerator
import scala.meta.internal.pc.printer.MetalsPrinter
import scala.meta.pc.OffsetParams
import scala.meta.pc.PresentationCompilerConfig
import scala.meta.pc.PresentationCompilerConfig.OverrideDefFormat
import scala.meta.pc.SymbolSearch

import dotty.tools.dotc.ast.tpd.Tree
import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Definitions
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.Flags.*
import dotty.tools.dotc.core.NameKinds.DefaultGetterName
import dotty.tools.dotc.core.NameKinds.NameKind
import dotty.tools.dotc.core.Names.Name
import dotty.tools.dotc.core.StdNames
import dotty.tools.dotc.core.SymDenotations.SymDenotation
import dotty.tools.dotc.core.Symbols.NoSymbol
import dotty.tools.dotc.core.Symbols.Symbol
import dotty.tools.dotc.core.Types.Type
import dotty.tools.dotc.interactive.Interactive
import dotty.tools.dotc.interactive.InteractiveDriver
import dotty.tools.dotc.util.SourceFile
import dotty.tools.dotc.util.SourcePosition
import dotty.tools.dotc.util.Spans.Span
import org.eclipse.{lsp4j as l}

object OverrideCompletions:
  private type TargetDef = TypeDef | DefDef

  private def defaultIndent(tabIndent: Boolean) =
    if tabIndent then 1 else 2

  /**
   * @param td A surrounded type definition being complete
   * @param filterName A prefix string for filtering, if None no filter
   * @param start The starting point of the completion. For example, starting point is `*`
   *              `*override def f|` (where `|` represents the cursor position).
   */
  def contribute(
      td: TypeDef,
      completing: Option[Symbol],
      start: Int,
      indexedContext: IndexedContext,
      search: SymbolSearch,
      config: PresentationCompilerConfig,
  ): List[CompletionValue] =
    import indexedContext.ctx
    val clazz = td.symbol.asClass
    val syntheticCoreMethods: Set[Name] =
      indexedContext.ctx.definitions.syntheticCoreMethods.map(_.name).toSet
    val isDecl = td.typeOpt.decls.toList.toSet

    /** Is the given symbol that we're trying to complete? */
    def isSelf(sym: Symbol) = completing.fold(false)(self => self == sym)

    def isOverrideable(sym: Symbol)(using Context): Boolean =
      val overridingSymbol = sym.overridingSymbol(clazz)
      !sym.is(Synthetic) &&
      !sym.is(Artifact) &&
      // not overridden in in this class, except overridden by the symbol that we're completing
      (!isDecl(overridingSymbol) || isSelf(overridingSymbol)) &&
      !(sym.is(Mutable) && !sym.is(
        Deferred
      )) && // concrete var can't be override
      (!syntheticCoreMethods(sym.name) || allowedList(sym.name)) &&
      !sym.is(Final) &&
      !sym.isConstructor &&
      !sym.isSetter &&
      // exclude symbols desugared by default args
      !sym.name.is(DefaultGetterName)
    end isOverrideable
    // Given the base class `trait Foo { def foo: Int; val bar: Int; var baz: Int }`
    // and typing `def @@` in the subclass of `Foo`,
    // suggest `def foo` and exclude `val bar`, and `var baz` from suggestion
    // because they are not method definitions (not starting from `def`).
    val flags = completing.map(_.flags & interestingFlags).getOrElse(EmptyFlags)

    // not using `td.tpe.abstractTermMembers` because those members includes
    // the abstract members in `td.tpe`. For example, when we type `def foo@@`,
    // `td.tpe.abstractTermMembers` contains `method foo: <error>` and it overrides the parent `foo` method.
    val overridables = td.tpe.parents
      .flatMap { parent =>
        parent.membersBasedOnFlags(
          flags,
          Flags.Private,
        )
      }
      .distinct
      .collect {
        case denot
            if completing
              .fold(true)(sym => denot.name.startsWith(sym.name.show)) &&
              !denot.symbol.isType =>
          denot.symbol
      }
      .filter(isOverrideable)

    overridables
      .map(sym =>
        toCompletionValue(
          sym.denot,
          start,
          td,
          indexedContext,
          search,
          shouldMoveCursor = true,
          config,
          indexedContext.ctx.compilationUnit.source.content
            .startsWith("o", start),
        )
      )
      .toList
  end contribute

  def implementAllAt(
      params: OffsetParams,
      driver: InteractiveDriver,
      search: SymbolSearch,
      config: PresentationCompilerConfig,
  ): ju.List[l.TextEdit] =
    object FindTypeDef:
      def unapply(path: List[Tree])(using Context): Option[TypeDef] = path match
        case (td: TypeDef) :: _ => Some(td)
        // new Iterable[Int] {}
        case (_: Ident) :: _ :: (_: Template) :: (td: TypeDef) :: _ =>
          Some(td)
        // new Context {}
        case (_: Ident) :: (_: Template) :: (td: TypeDef) :: _ =>
          Some(td)
        case (_: Ident) :: (_: New) :: (_: Select) :: (_: Apply) :: (_: Template) :: (td: TypeDef) :: _ =>
          Some(td)
        case (dd: DefDef) :: (_: Template) :: (td: TypeDef) :: _
            if dd.symbol.isConstructor =>
          Some(td)
        case _ => None
    end FindTypeDef

    val uri = params.uri
    val ctx = driver.currentCtx
    driver.run(
      uri,
      SourceFile.virtual(uri.toASCIIString, params.text),
    )
    val unit = driver.currentCtx.run.units.head
    val tree = unit.tpdTree
    val pos = driver.sourcePosition(params)

    val newctx = driver.currentCtx.fresh.setCompilationUnit(unit)
    val path =
      Interactive.pathTo(newctx.compilationUnit.tpdTree, pos.span)(using newctx)
    val indexedContext = IndexedContext(
      MetalsInteractive.contextOfPath(path)(using newctx)
    )
    import indexedContext.ctx

    lazy val autoImportsGen = AutoImports.generator(
      pos,
      params.text,
      unit.tpdTree,
      indexedContext,
      config,
    )
    lazy val implementAll = implementAllFor(
      indexedContext,
      params.text,
      search,
      autoImportsGen,
      config,
    )
    path match
      case (_: Ident) :: (dd: DefDef) :: _ =>
        implementAll(dd).asJava
      case FindTypeDef(td) =>
        implementAll(td).asJava
      case _ =>
        new ju.ArrayList[l.TextEdit]()
  end implementAllAt

  private def implementAllFor(
      indexedContext: IndexedContext,
      text: String,
      search: SymbolSearch,
      autoImports: AutoImportsGenerator,
      config: PresentationCompilerConfig,
  )(
      defn: TargetDef
  )(using Context): List[l.TextEdit] =
    def calcIndent(
        defn: TargetDef,
        decls: List[Symbol],
        source: SourceFile,
        text: String,
        shouldCompleteBraces: Boolean,
    )(using Context): (String, String, String) =
      // For `FooImpl` in the below, the necessaryIndent will be 2
      // because there're 2 spaces before `class FooImpl`.
      // ```scala
      // |object X:
      // |  class FooImpl extends Foo {
      // |  }
      // ```
      val (necessaryIndent, tabIndented) = CompletionPos.inferIndent(
        source.lineToOffset(defn.sourcePos.line),
        text,
      )
      // infer indent for implementations
      // If there's declaration in the class/object, follow its indent.
      // For example, numIndent will be 8, because there're 8 spaces before
      // `override def foo: Int`
      // ```scala
      // |object X:
      // |  class FooImpl extends Foo {
      // |        override def foo: Int = 1
      // |  }
      // ```
      val (numIndent, shouldTabIndent) =
        decls.headOption
          .map { decl =>
            CompletionPos.inferIndent(
              source.lineToOffset(decl.sourcePos.line),
              text,
            )
          }
          .getOrElse({
            val default = defaultIndent(tabIndented)
            (necessaryIndent + default, tabIndented)
          })
      val indentChar = if shouldTabIndent then "\t" else " "
      val indent = indentChar * numIndent
      val lastIndent =
        if (defn.sourcePos.startLine == defn.sourcePos.endLine) ||
          shouldCompleteBraces
        then "\n" + indentChar * necessaryIndent
        else ""
      (indent, indent, lastIndent)
    end calcIndent

    val overridables = defn.tpe.abstractTermMembers.map(_.symbol)
    val completionValues = overridables
      .map(sym =>
        toCompletionValue(
          sym.denot,
          0, // we don't care the position of each completion value from ImplementAll
          defn,
          indexedContext,
          search,
          shouldMoveCursor = false,
          config,
          shouldAddOverrideKwd = true,
        )
      )
      .toList

    val (edits, imports) = toEdits(
      completionValues,
      autoImports,
    )

    if edits.isEmpty then Nil
    else
      // A list of declarations in the class/object to implement
      val decls = defn.tpe.decls.toList
        .filter(sym =>
          !sym.isPrimaryConstructor &&
            !sym.isTypeParam &&
            !sym.is(ParamAccessor) && // `num` of `class Foo(num: int)`
            sym.span.exists &&
            defn.sourcePos.contains(sym.sourcePos)
        )
        .sortBy(_.sourcePos.start)

      val source = indexedContext.ctx.source

      val shouldCompleteBraces = decls.isEmpty && hasBraces(text, defn).isEmpty

      val (startIndent, indent, lastIndent) =
        calcIndent(defn, decls, source, text, shouldCompleteBraces)

      // If there're declarations in the class/object to implement e.g.
      // ```scala
      // class FooImpl extends Foo:
      //   override def foo(...) = ...
      // ```
      // The edit position will be the beginning line of `override def foo`
      // Otherwise, infer the position by `inferEditPosiiton`
      val posFromDecls =
        decls.headOption.map(decl =>
          val pos = source.lineToOffset(decl.sourcePos.line)
          val span = decl.sourcePos.span.withStart(pos).withEnd(pos)
          defn.sourcePos.withSpan(span)
        )
      val editPos = posFromDecls.getOrElse(inferEditPosition(text, defn))

      lazy val shouldCompleteWith = defn match
        case dd: DefDef =>
          dd.symbol.is(Given)
        case _ => false

      val (start, last) =
        val (startNL, lastNL) =
          if posFromDecls.nonEmpty then ("\n", "\n\n") else ("\n\n", "\n")
        if shouldCompleteWith then
          (s" with$startNL$indent", s"$lastNL$lastIndent")
        else if shouldCompleteBraces then
          (s" {$startNL$indent", s"$lastNL$lastIndent}")
        else (s"$startNL$indent", s"$lastNL$lastIndent")

      val newEdit =
        edits.mkString(start, s"\n\n$indent", last)
      val implementAll = new l.TextEdit(
        editPos.toLSP,
        newEdit,
      )
      implementAll +: imports.toList
    end if

  end implementAllFor

  private def toEdits(
      completions: List[CompletionValue.Override],
      autoImports: AutoImportsGenerator,
  ): (List[String], Set[l.TextEdit]) =
    completions.foldLeft(
      (List.empty[String], Set.empty[l.TextEdit])
    ) { (editsAndImports, completion) =>
      val edit =
        completion.value
      val edits = editsAndImports._1 :+ edit
      val imports = completion.shortenedNames
        .sortBy(nme => nme.name)
        .flatMap(name => autoImports.forShortName(name))
        .flatten
        .toSet ++ editsAndImports._2
      (edits, imports)
    }
  end toEdits

  private lazy val allowedList: Set[Name] =
    Set[Name](
      StdNames.nme.hashCode_,
      StdNames.nme.toString_,
      StdNames.nme.equals_,
    )

  private def toCompletionValue(
      sym: SymDenotation,
      start: Int,
      defn: TargetDef,
      indexedContext: IndexedContext,
      search: SymbolSearch,
      shouldMoveCursor: Boolean,
      config: PresentationCompilerConfig,
      shouldAddOverrideKwd: Boolean,
  )(using Context): CompletionValue.Override =
    val renames = AutoImport.renameConfigMap(config)
    val printer = MetalsPrinter.standard(
      indexedContext,
      search,
      includeDefaultParam = MetalsPrinter.IncludeDefaultParam.Never,
      renames,
    )
    val overrideKeyword: String =
      // if the overriding method is not an abstract member, add `override` keyword
      if !sym.isOneOf(Deferred) || shouldAddOverrideKwd
      then "override"
      else ""

    val overrideDefLabel: String = config.overrideDefFormat() match
      case OverrideDefFormat.Unicode =>
        if sym.is(Deferred) then "🔼 "
        else "⏫ "
      case _ => ""

    val signature =
      // `iterator` method in `new Iterable[Int] { def iterato@@ }`
      // should be completed as `def iterator: Iterator[Int]` instead of `Iterator[A]`.
      val seenFrom = defn.tpe.memberInfo(sym.symbol)
      if sym.is(Method) then
        printer.defaultMethodSignature(
          sym.symbol,
          seenFrom,
          additionalMods =
            if overrideKeyword.nonEmpty then List(overrideKeyword) else Nil,
        )
      else
        printer.defaultValueSignature(
          sym.symbol,
          seenFrom,
          additionalMods =
            if overrideKeyword.nonEmpty then List(overrideKeyword) else Nil,
        )
    end signature

    val label = s"$overrideDefLabel$signature"
    val stub =
      if config.isCompletionSnippetsEnabled && shouldMoveCursor then "${0:???}"
      else "???"
    val value = s"$signature = $stub"
    val filterText = signature
    CompletionValue.Override(
      label,
      value,
      sym.symbol,
      printer.shortenedNames,
      Some(filterText),
      start,
    )
  end toCompletionValue

  private val interestingFlags = Flags.Method | Flags.Mutable

  /**
   * Infer the editPosition for "implement all" code action for the given TypeDef.
   *
   * If there're braces like `class FooImpl extends Foo {}`,
   * editPosition will be inside the braces.
   * Otherwise, e.g. `class FooImpl extends Foo`, editPosition will be
   * after the `extends Foo`.
   *
   * @param text the whole text of the source file
   * @param td the class/object to impement all
   */
  private def inferEditPosition(text: String, defn: TargetDef)(using
      Context
  ): SourcePosition =
    val span = hasBraces(text, defn)
      .map { offset =>
        defn.sourcePos.span.withStart(offset + 1).withEnd(offset + 1)
      }
      .getOrElse({
        defn.sourcePos.span.withStart(defn.span.end)
      })
    defn.sourcePos.withSpan(span)
  end inferEditPosition

  private def hasBraces(text: String, defn: TargetDef): Option[Int] =
    def hasSelfTypeAnnot = defn match
      case td: TypeDef =>
        td.rhs match
          case t: Template =>
            t.self.span.isSourceDerived
          case _ => false
      case _ => false
    val start = defn.span.start
    val offset =
      if hasSelfTypeAnnot then text.indexOf("=>", start) + 1
      else text.indexOf("{", start)
    if offset > 0 && offset < defn.span.end then Some(offset)
    else None
  end hasBraces

end OverrideCompletions
