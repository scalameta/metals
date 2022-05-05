package scala.meta.internal.pc
package completions

import scala.meta.internal.pc.printer.MetalsPrinter
import scala.meta.pc.PresentationCompilerConfig
import scala.meta.pc.PresentationCompilerConfig.OverrideDefFormat

import dotty.tools.dotc.ast.tpd.Template
import dotty.tools.dotc.ast.tpd.Tree
import dotty.tools.dotc.ast.tpd.TypeDef
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Definitions
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.Flags.*
import dotty.tools.dotc.core.NameKinds.DefaultGetterName
import dotty.tools.dotc.core.Names.Name
import dotty.tools.dotc.core.StdNames
import dotty.tools.dotc.core.SymDenotations.SymDenotation
import dotty.tools.dotc.core.Symbols.NoSymbol
import dotty.tools.dotc.core.Symbols.Symbol
import dotty.tools.dotc.core.Types.Type
import dotty.tools.dotc.util.SourcePosition

object OverrideCompletions:
  /**
   * @param td A surrounded type definition being complete
   * @param filterName A prefix string for filtering, if None no filter
   */
  def contribute(
      td: TypeDef,
      completing: Option[Symbol],
      start: Int,
      indexedContext: IndexedContext,
      config: PresentationCompilerConfig
  ): List[CompletionValue] =
    import indexedContext.ctx
    val clazz = td.symbol.asClass
    val syntheticCoreMethods: Set[Name] =
      indexedContext.ctx.definitions.syntheticCoreMethods.map(_.name).toSet
    val isDecl = td.typeOpt.decls.toList.toSet

    /** Is the given symbol that we're trying to complete? */
    def isSelf(sym: Symbol) = completing.fold(false)(self => self == sym)

    val flags = completing.map(_.flags & interestingFlags).getOrElse(EmptyFlags)

    def isOverrideable(sym: Symbol)(using Context): Boolean =
      val overridingSymbol = sym.overridingSymbol(clazz)
      !sym.is(Synthetic) &&
      !sym.is(Artifact) &&
      // not overridden in in this class, except overridden by the symbol that we're completing
      (!isDecl(overridingSymbol) || isSelf(overridingSymbol)) &&
      !(sym.is(Mutable) && !sym.is(Deferred)) && // concrete var
      (!syntheticCoreMethods(sym.name) || allowedList(sym.name)) &&
      !sym.is(Final) &&
      !sym.isConstructor &&
      !sym.isSetter &&
      // exclude symbols desugared by default args
      !sym.name.is(DefaultGetterName)
    end isOverrideable

    val overridables = td.tpe.parents
      .flatMap { parent =>
        parent.membersBasedOnFlags(
          flags,
          Flags.Private
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
          config,
          indexedContext.ctx.compilationUnit.source.content
            .startsWith("o", start)
        )
      )
      .toList
  end contribute

  private lazy val allowedList: Set[Name] =
    Set[Name](
      StdNames.nme.hashCode_,
      StdNames.nme.toString_,
      StdNames.nme.equals_
    )

  private def toCompletionValue(
      sym: SymDenotation,
      start: Int,
      td: TypeDef,
      indexedContext: IndexedContext,
      config: PresentationCompilerConfig,
      shouldAddOverrideKwd: Boolean
  )(using Context): CompletionValue.Override =
    val printer = MetalsPrinter.standard(indexedContext)
    val overrideKeyword: String =
      if !sym.isOneOf(Deferred) || shouldAddOverrideKwd
      then "override "
      // Don't insert `override` keyword if the supermethod is abstract and the
      // user did not explicitly type starting with o . See:
      // https://github.com/scalameta/metals/issues/565#issuecomment-472761240
      else ""

    val asciOverrideDef: String =
      if sym.is(Abstract) then ""
      else overrideKeyword

    val overrideDef: String = config.overrideDefFormat() match
      case OverrideDefFormat.Unicode =>
        if sym.is(Abstract) then "🔼 "
        else "⏫ "
      case _ => asciOverrideDef

    val mods = if sym.is(Lazy) then "lazy " else ""

    val signature =
      // A type of `iterator` in `new Iterable[Int] { def iterato@@ }`
      // should be seen from `Iterable[Int]` and
      // complete `def iterator: Iterator[Int]` instead of `Iterator[A]`.
      val seenFrom = td.tpe.memberInfo(sym.symbol)
      if sym.is(Method) then
        printer.defaultMethodSignature(
          sym.symbol,
          seenFrom
        )
      else if sym.is(Mutable) then
        s"var ${sym.name.show}: ${printer.tpe(seenFrom)}"
      else s"val ${sym.name.show}: ${printer.tpe(seenFrom)}"

    val label = overrideDef + signature
    val stub = if config.isCompletionSnippetsEnabled then "${0:???}" else "???"
    val value = s"${overrideKeyword}${mods}${signature} = $stub"
    CompletionValue.Override(
      label,
      value,
      sym.symbol,
      printer.shortenedNames,
      start
    )
  end toCompletionValue

  private val interestingFlags = Flags.Method | Flags.Mutable

end OverrideCompletions
