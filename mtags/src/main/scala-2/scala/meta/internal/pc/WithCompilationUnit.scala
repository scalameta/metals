package scala.meta.internal.pc

import scala.meta.pc.OffsetParams
import scala.meta.pc.VirtualFileParams

class WithCompilationUnit(
    val compiler: MetalsGlobal,
    val params: VirtualFileParams,
    val shouldRemoveCompilationUnitAfterUse: Boolean = false
) {
  import compiler._
  protected val unit: RichCompilationUnit = addCompilationUnit(
    code = params.text(),
    filename = params.uri().toString(),
    cursor = None,
    willBeRemovedAfterUsing = shouldRemoveCompilationUnitAfterUse
  )
  protected val offset: Int = params match {
    case p: OffsetParams => p.offset()
    case _: VirtualFileParams => 0
  }
  val pos: Position = unit.position(offset)
  lazy val text = unit.source.content
  typeCheck(unit)

  protected lazy val namedArgCache: Map[Position, Tree] = {
    val parsedTree = parseTree(unit.source)
    parsedTree.collect { case arg @ AssignOrNamedArg(_, rhs) =>
      rhs.pos -> arg
    }.toMap
  }

  /**
   * Find all symbols that should be shown together.
   * For example class we want to also show companion object.
   *
   * @param sym symbol to find the alternative candidates for
   * @return set of possible symbols
   */
  protected def symbolAlternatives(sym: Symbol): Set[Symbol] = {
    val all =
      if (sym.isClass) {
        if (sym.owner.isMethod) Set(sym) ++ sym.localCompanion(pos)
        else Set(sym, sym.companionModule, sym.companion.moduleClass)
      } else if (sym.isModuleOrModuleClass) {
        if (sym.owner.isMethod) Set(sym) ++ sym.localCompanion(pos)
        else Set(sym, sym.companionClass, sym.moduleClass)
      } else if (sym.isTerm && (sym.owner.isClass || sym.owner.isConstructor)) {
        val info =
          if (sym.owner.isClass) sym.owner.info
          else sym.owner.owner.info
        val additionalSymbols =
          Set(
            info.member(sym.getterName),
            info.member(sym.setterName),
            info.member(sym.localName)
          )
        // check if the symbol is actually getter/setter/local value, otherwise we should not show it
        val base =
          if (additionalSymbols(sym))
            additionalSymbols + sym
          else
            Set(sym)
        base ++ constructorParam(sym) ++ sym.allOverriddenSymbols.toSet
      } else Set(sym)
    all.filter(s => s != NoSymbol && !s.isError)
  }

  private def constructorParam(
      symbol: Symbol
  ): Set[Symbol] = {
    if (symbol.owner.isClass) {
      val info = symbol.owner.info.member(nme.CONSTRUCTOR).info
      info.paramss.flatten.find(_.name == symbol.name).toSet
    } else Set.empty
  }

  protected def collectArguments(apply: Apply): List[Tree] = {
    apply.fun match {
      case appl: Apply => collectArguments(appl) ++ apply.args
      case _ => apply.args
    }
  }

  protected def fallbackSymbol(name: Name, pos: Position): Option[Symbol] = {
    val context = doLocateImportContext(pos)
    context.lookupSymbol(name, sym => sym.isType) match {
      case LookupSucceeded(_, symbol) =>
        Some(symbol)
      case _ => None
    }
  }

  def cleanUp(): Unit =
    if (shouldRemoveCompilationUnitAfterUse)
      removeAfterUsing(unit.source.file)

}
