package scala.meta.internal.pc

import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.internal.pc.MetalsInteractive.ExtensionMethodCall
import scala.meta.internal.pc.PcSymbolSearch.*

import dotty.tools.dotc.ast.NavigateAST
import dotty.tools.dotc.ast.Positioned
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.ast.untpd.ExtMethods
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.NameOps.*
import dotty.tools.dotc.core.Names.*
import dotty.tools.dotc.core.StdNames.*
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.interactive.Interactive
import dotty.tools.dotc.util.SourcePosition
import dotty.tools.dotc.util.Spans.Span

trait PcSymbolSearch:
  self: WithCompilationUnit =>

  private val caseClassSynthetics: Set[Name] = Set(nme.apply, nme.copy)

  lazy val rawPath =
    Interactive
      .pathTo(driver.openedTrees(uri), pos)(using driver.currentCtx)
      .dropWhile(t => // NamedArg anyway doesn't have symbol
        t.symbol == NoSymbol && !t.isInstanceOf[NamedArg] ||
          // same issue https://github.com/lampepfl/dotty/issues/15937 as below
          t.isInstanceOf[TypeTree]
      )

  lazy val extensionMethods =
    NavigateAST
      .untypedPath(pos.span)(using compilatonUnitContext)
      .collectFirst { case em @ ExtMethods(_, _) => em }

  lazy val path = rawPath match
    // For type it will sometimes go into the wrong tree since TypeTree also contains the same span
    // https://github.com/lampepfl/dotty/issues/15937
    case TypeApply(sel: Select, _) :: tail if sel.span.contains(pos.span) =>
      Interactive.pathTo(sel, pos.span) ::: rawPath
    case _ => rawPath

  lazy val soughtSymbols: Option[(Set[Symbol], SourcePosition)] = soughtSymbols(
    path
  )

  def soughtSymbols(path: List[Tree]): Option[(Set[Symbol], SourcePosition)] =
    val sought = path match
      /* reference of an extension paramter
       * extension [EF](<<xs>>: List[EF])
       *   def double(ys: List[EF]) = <<x@@s>> ++ ys
       */
      case (id: Ident) :: _
          if id.symbol
            .is(Flags.Param) && id.symbol.owner.is(Flags.ExtensionMethod) =>
        Some(findAllExtensionParamSymbols(id.sourcePos, id.name, id.symbol))
      /**
       * Workaround for missing symbol in:
       * class A[T](a: T)
       * val x = new <<A>>(1)
       */
      case t :: (n: New) :: (sel: Select) :: _
          if t.symbol == NoSymbol && sel.symbol.isConstructor =>
        Some(symbolAlternatives(sel.symbol.owner), namePos(t))
      /**
       * Workaround for missing symbol in:
       * class A[T](a: T)
       * val x = <<A>>[Int](1)
       */
      case (sel @ Select(New(t), _)) :: (_: TypeApply) :: _
          if sel.symbol.isConstructor =>
        Some(symbolAlternatives(sel.symbol.owner), namePos(t))

      /* simple identifier:
       * val a = val@@ue + value
       */
      case (id: Ident) :: _ =>
        Some(symbolAlternatives(id.symbol), id.sourcePos)
      /* simple selector:
       * object.val@@ue
       */
      case (sel: Select) :: _ if selectNameSpan(sel).contains(pos.span) =>
        Some(symbolAlternatives(sel.symbol), pos.withSpan(sel.nameSpan))
      /* named argument:
       * foo(nam@@e = "123")
       */
      case (arg: NamedArg) :: (appl: Apply) :: _ =>
        val realName = arg.name.stripModuleClassSuffix.lastPart
        if pos.span.start > arg.span.start && pos.span.end < arg.span.point + realName.length
        then
          val length = realName.toString.backticked.length()
          val pos = arg.sourcePos.withSpan(
            arg.span
              .withEnd(arg.span.start + length)
              .withPoint(arg.span.start)
          )
          appl.symbol.paramSymss.flatten.find(_.name == arg.name).map { s =>
            // if it's a case class we need to look for parameters also
            if caseClassSynthetics(s.owner.name) && s.owner.is(Flags.Synthetic)
            then
              (
                Set(
                  s,
                  s.owner.owner.companion.info.member(s.name).symbol,
                  s.owner.owner.info.member(s.name).symbol,
                )
                  .filter(_ != NoSymbol),
                pos,
              )
            else (Set(s), pos)
          }
        else None
        end if
      /* all definitions:
       * def fo@@o = ???
       * class Fo@@o = ???
       * etc.
       */
      case (df: NamedDefTree) :: _
          if df.nameSpan
            .contains(pos.span) && !isGeneratedGiven(df, sourceText) =>
        Some(symbolAlternatives(df.symbol), pos.withSpan(df.nameSpan))
      /* enum cases with params
       * enum Foo:
       *  case B@@ar[A](i: A)
       */
      case (df: NamedDefTree) :: Template(_, _, self, _) :: _
          if (df.name == nme.apply || df.name == nme.unapply) && df.nameSpan.isZeroExtent =>
        Some(symbolAlternatives(self.tpt.symbol), self.sourcePos)
      /**
       * For traversing annotations:
       * @JsonNo@@tification("")
       * def params() = ???
       */
      case (df: MemberDef) :: _ if df.span.contains(pos.span) =>
        val annotTree = df.mods.annotations.find { t =>
          t.span.contains(pos.span)
        }
        collectTrees(annotTree).flatMap { t =>
          soughtSymbols(
            Interactive.pathTo(t, pos.span)
          )
        }.headOption

      /* Import selectors:
       * import scala.util.Tr@@y
       */
      case (imp: Import) :: _ if imp.span.contains(pos.span) =>
        imp
          .selector(pos.span)
          .map(sym => (symbolAlternatives(sym), sym.sourcePos))

      /* Workaround for missing span in:
       * class MyIntOut(val value: Int)
       * object MyIntOut:
       *   extension (i: MyIntOut) def <<uneven>> = i.value % 2 == 1
       *
       * val a = MyIntOut(1).<<un@@even>>
       */
      case ExtensionMethodCall(id) :: _ if id.span.contains(pos.span) =>
        Some(symbolAlternatives(id.symbol), id.sourcePos)
      case _ => None

    sought match
      case None => seekInExtensionParameters()
      case _ => sought

  end soughtSymbols

  // First identify the symbol we are at, comments identify @@ as current cursor position
  def symbolAlternatives(sym: Symbol) =
    def member(parent: Symbol) = parent.info.member(sym.name).symbol
    def primaryConstructorTypeParam(owner: Symbol) =
      for
        typeParams <- owner.primaryConstructor.paramSymss.headOption
        param <- typeParams.find(_.name == sym.name)
        if (param.isType)
      yield param
    def additionalForEnumTypeParam(enumClass: Symbol) =
      if enumClass.is(Flags.Enum) then
        val enumOwner =
          if enumClass.is(Flags.Case)
          then
            // we check that the type parameter is the one from enum class
            // and not an enum case type parameter with the same name
            Option.when(member(enumClass).is(Flags.Synthetic))(
              enumClass.maybeOwner.companionClass
            )
          else Some(enumClass)
        enumOwner.toSet.flatMap { enumOwner =>
          val symsInEnumCases = enumOwner.children.toSet.flatMap(enumCase =>
            if member(enumCase).is(Flags.Synthetic)
            then primaryConstructorTypeParam(enumCase)
            else None
          )
          val symsInEnumOwner =
            primaryConstructorTypeParam(enumOwner).toSet + member(enumOwner)
          symsInEnumCases ++ symsInEnumOwner
        }
      else Set.empty
    val all =
      if sym.is(Flags.ModuleClass) then
        Set(sym, sym.companionModule, sym.companionModule.companion)
      else if sym.isClass then
        Set(sym, sym.companionModule, sym.companion.moduleClass)
      else if sym.is(Flags.Module) then
        Set(sym, sym.companionClass, sym.moduleClass)
      else if sym.isTerm && (sym.owner.isClass || sym.owner.isConstructor)
      then
        val info =
          if sym.owner.isClass then sym.owner.info else sym.owner.owner.info
        Set(
          sym,
          info.member(sym.asTerm.name.setterName).symbol,
          info.member(sym.asTerm.name.getterName).symbol,
        ) ++ sym.allOverriddenSymbols.toSet
      // type used in primary constructor will not match the one used in the class
      else if sym.isTypeParam && sym.owner.isPrimaryConstructor then
        Set(sym, member(sym.maybeOwner.maybeOwner))
          ++ additionalForEnumTypeParam(sym.maybeOwner.maybeOwner)
      else if sym.isTypeParam then
        primaryConstructorTypeParam(sym.maybeOwner).toSet
          ++ additionalForEnumTypeParam(sym.maybeOwner) + sym
      else Set(sym)
    all.filter(s => s != NoSymbol && !s.isError)
  end symbolAlternatives

  private def seekInExtensionParameters() =
    def collectParams(
        extMethods: ExtMethods
    ): Option[ExtensionParamOccurence] =
      MetalsNavigateAST
        .pathToExtensionParam(pos.span, extMethods)(using compilatonUnitContext)
        .collectFirst {
          case v: untpd.ValOrTypeDef =>
            ExtensionParamOccurence(
              v.name,
              v.namePos,
              v.symbol,
              extMethods.methods,
            )
          case i: untpd.Ident =>
            ExtensionParamOccurence(
              i.name,
              i.sourcePos,
              i.symbol,
              extMethods.methods,
            )
        }

    for
      extensionMethodScope <- extensionMethods
      occurence <- collectParams(extensionMethodScope)
      symbols <- collectAllExtensionParamSymbols(
        path.headOption.getOrElse(unit.tpdTree),
        occurence,
      )
    yield symbols
  end seekInExtensionParameters

  private def collectAllExtensionParamSymbols(
      tree: tpd.Tree,
      occurrence: ExtensionParamOccurence,
  ): Option[(Set[Symbol], SourcePosition)] =
    occurrence match
      case ExtensionParamOccurence(_, namePos, symbol, _)
          if symbol != NoSymbol && !symbol.isError && !symbol.owner.is(
            Flags.ExtensionMethod
          ) =>
        Some((symbolAlternatives(symbol), namePos))
      case ExtensionParamOccurence(name, namePos, _, methods) =>
        val symbols =
          for
            method <- methods.toSet
            symbol <-
              Interactive.pathTo(tree, method.span) match
                case (d: DefDef) :: _ =>
                  d.paramss.flatten.collect {
                    case param if param.name.decoded == name.decoded =>
                      param.symbol
                  }
                case _ => Set.empty[Symbol]
            if (symbol != NoSymbol && !symbol.isError)
            withAlt <- symbolAlternatives(symbol)
          yield withAlt
        if symbols.nonEmpty then Some((symbols, namePos)) else None
  end collectAllExtensionParamSymbols

  private def findAllExtensionParamSymbols(
      pos: SourcePosition,
      name: Name,
      sym: Symbol,
  ) =
    val symbols =
      for
        methods <- extensionMethods.map(_.methods)
        symbols <- collectAllExtensionParamSymbols(
          unit.tpdTree,
          ExtensionParamOccurence(name, pos, sym, methods),
        )
      yield symbols
    symbols.getOrElse((symbolAlternatives(sym), pos))
  end findAllExtensionParamSymbols
end PcSymbolSearch

object PcSymbolSearch:
  // NOTE: Connected to https://github.com/lampepfl/dotty/issues/16771
  // `sel.nameSpan` is calculated incorrectly in (1 + 2).toString
  // See test DocumentHighlightSuite.select-parentheses
  def selectNameSpan(sel: Select): Span =
    val span = sel.span
    if span.exists then
      val point = span.point
      if sel.name.toTermName == nme.ERROR then Span(point)
      else if sel.qualifier.span.start > span.point then // right associative
        val realName = sel.name.stripModuleClassSuffix.lastPart
        Span(span.start, span.start + realName.length, point)
      else Span(point, span.end, point)
    else span

  def collectTrees(trees: Iterable[Positioned]): Iterable[Tree] =
    trees.collect { case t: Tree => t }

  def namePos(tree: Tree)(using Context): SourcePosition =
    tree match
      case sel: Select => sel.sourcePos.withSpan(selectNameSpan(sel))
      case _ => tree.sourcePos

  def isGeneratedGiven(df: NamedDefTree, sourceText: String)(using Context) =
    val nameSpan = df.nameSpan
    df.symbol.is(Flags.Given) && sourceText.substring(
      nameSpan.start,
      nameSpan.end,
    ) != df.name.toString()

end PcSymbolSearch
