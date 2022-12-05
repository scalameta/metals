package scala.meta.internal.pc

import scala.meta.pc.OffsetParams

abstract class PcCollector[T](
    val compiler: MetalsGlobal,
    params: OffsetParams
) {
  import compiler._

  def collect(tree: Tree, pos: Position): T

  val unit: RichCompilationUnit = addCompilationUnit(
    code = params.text(),
    filename = params.uri().toString(),
    cursor = None
  )
  val pos: Position = unit.position(params.offset)
  val text = unit.source.content
  private val caseClassSynthetics: Set[Name] = Set(nme.apply, nme.copy)
  typeCheck(unit)
  val typedTree: Tree = locateTree(pos) match {
    // Check actual object if apply is synthetic
    case sel @ Select(qual, name) if name == nme.apply && qual.pos == sel.pos =>
      qual
    case Import(expr, _) if expr.pos.includes(pos) =>
      // imports seem to be marked as transparent
      locateTree(pos, expr, acceptTransparent = true)
    case t => t
  }

  /**
   * Find all symbols that should be shown together.
   * For example class we want to also show companion object.
   *
   * @param sym symbol to find the alternative candidates for
   * @return set of possible symbols
   */
  def symbolAlternatives(sym: Symbol): Set[Symbol] = {
    val all =
      if (sym.isClass) {
        if (sym.owner.isMethod) Set(sym) ++ localCompanion(sym)
        else Set(sym, sym.companionModule, sym.companion.moduleClass)
      } else if (sym.isModuleOrModuleClass) {
        if (sym.owner.isMethod) Set(sym) ++ localCompanion(sym)
        else Set(sym, sym.companionClass, sym.moduleClass)
      } else if (sym.isTerm) {
        val info =
          if (sym.owner.isClass) sym.owner.info
          else sym.owner.owner.info
        Set(
          sym,
          info.member(sym.getterName),
          info.member(sym.setterName),
          info.member(sym.localName)
        ) ++ sym.allOverriddenSymbols.toSet
      } else Set(sym)
    all.filter(s => s != NoSymbol && !s.isError)
  }

  /**
   * For classes defined in methods it's not possible to find
   * companion via methods in symbol.
   *
   * @param sym symbol to find a companion for
   * @return companion if it exists
   */
  def localCompanion(sym: Symbol): Option[Symbol] = {
    val nameToLookFor =
      if (sym.isModuleClass) sym.name.companionName.companionName
      else sym.name.companionName
    val context = doLocateImportContext(pos)
    context.lookupSymbol(
      nameToLookFor,
      s => s.owner == sym.owner
    ) match {
      case LookupSucceeded(_, symbol) =>
        Some(symbol)
      case _ => None
    }
  }
  def adjust(
      pos: Position,
      forHighlight: Boolean = false
  ): (Position, Boolean) = {
    val isBackticked = text(pos.start) == '`' && text(pos.end - 1) == '`'
    // when the old name contains backticks, the position is incorrect
    val isOldNameBackticked = text(pos.start) == '`' &&
      text(pos.end - 1) != '`' &&
      text(pos.end + 1) == '`'
    if (isBackticked && !forHighlight)
      (pos.withStart(pos.start + 1).withEnd(pos.end - 1), true)
    else if (isOldNameBackticked) // pos
      (pos.withEnd(pos.end + 2), false)
    else (pos, false)
  }

  private lazy val namedArgCache = {
    val parsedTree = parseTree(unit.source)
    parsedTree.collect { case arg @ AssignOrNamedArg(_, rhs) =>
      rhs.pos.start -> arg
    }.toMap
  }
  def fallbackSymbol(name: Name, pos: Position): Option[Symbol] = {
    val context = doLocateImportContext(pos)
    context.lookupSymbol(name, sym => sym.isType) match {
      case LookupSucceeded(_, symbol) =>
        Some(symbol)
      case _ => None
    }
  }

  // First identify the symbol we are at, comments identify @@ as current cursor position
  lazy val soughtSymbols: Option[(Set[Symbol], Position)] = typedTree match {
    /* simple identifier:
     * val a = val@@ue + value
     */
    case (id: Ident) =>
      // might happen in type trees
      // also this doesn't seem to be picked up by semanticdb
      if (id.symbol == NoSymbol)
        fallbackSymbol(id.name, pos).map(sym =>
          (symbolAlternatives(sym), id.pos)
        )
      else {
        Some(symbolAlternatives(id.symbol), id.pos)
      }
    /* named argument, which is a bit complex:
     * foo(nam@@e = "123")
     */
    case (apply: Apply) =>
      apply.args
        .flatMap { arg =>
          namedArgCache.get(arg.pos.start)
        }
        .collectFirst {
          case AssignOrNamedArg(id: Ident, _) if id.pos.includes(pos) =>
            apply.symbol.paramss.flatten.find(_.name == id.name).map { s =>
              // if it's a case class we need to look for parameters also
              if (caseClassSynthetics(s.owner.name) && s.owner.isSynthetic) {
                val applyOwner = s.owner.owner
                val constructorOwner =
                  if (applyOwner.isCaseClass) applyOwner
                  else
                    applyOwner.companion match {
                      case NoSymbol =>
                        localCompanion(applyOwner).getOrElse(NoSymbol)
                      case comp => comp
                    }
                val info = constructorOwner.info
                val constructorParams = info.members
                  .filter(_.isConstructor)
                  .flatMap(_.paramss)
                  .flatten
                  .toSet
                (
                  (constructorParams ++ Set(
                    s,
                    info.member(s.getterName),
                    info.member(s.setterName),
                    info.member(s.localName)
                  )).filter(_ != NoSymbol),
                  id.pos
                )
              } else (Set(s), id.pos)
            }
        }
        .flatten
    /* all definitions:
     * def fo@@o = ???
     * class Fo@@o = ???
     * etc.
     */
    case (df: DefTree) if df.namePos.includes(pos) =>
      Some(symbolAlternatives(df.symbol), df.namePos)
    /* Import selectors:
     * import scala.util.Tr@@y
     */
    case (imp: Import) if imp.pos.includes(pos) =>
      imp.selector(pos).map(sym => (symbolAlternatives(sym), sym.pos))
    /* simple selector:
     * object.val@@ue
     */
    case (sel: NameTree) if sel.namePos.includes(pos) =>
      Some(symbolAlternatives(sel.symbol), sel.namePos)

    // needed for classOf[AB@@C]`
    case lit @ Literal(Constant(TypeRef(_, sym, _))) if lit.pos.includes(pos) =>
      val posStart = text.indexOfSlice(sym.decodedName, lit.pos.start)

      Some(
        symbolAlternatives(sym),
        lit.pos
          .withStart(posStart)
          .withEnd(posStart + sym.decodedName.length())
      )
    case _ =>
      None
  }

  def result(): List[T] = {

    // Now find all matching symbols in the document, comments identify <<>> as the symbol we are looking for
    soughtSymbols match {
      case Some((sought, _)) =>
        val owners = sought
          .map(_.owner)
          .flatMap(o => symbolAlternatives(o))
          .filter(_ != NoSymbol)
        val soughtNames: Set[Name] = sought.map(_.name)
        /*
         * For comprehensions have two owners, one for the enumerators and one for
         * yield. This is a heuristic to find that out.
         */
        def isForComprehensionOwner(named: NameTree) =
          soughtNames(named.name) &&
            named.symbol.owner.isAnonymousFunction && owners.exists(
              _.pos.point == named.symbol.owner.pos.point
            )

        def soughtOrOverride(sym: Symbol) =
          sought(sym) || sym.allOverriddenSymbols.exists(sought(_))

        def traverse(
            acc: Set[T],
            tree: Tree
        ): Set[T] = {
          tree match {
            /**
             * All indentifiers such as:
             * val a = <<b>>
             */
            case ident: Ident
                if ident.pos.isRange &&
                  (soughtOrOverride(ident.symbol) ||
                    isForComprehensionOwner(ident)) =>
              acc + collect(ident, ident.pos)

            /**
             * Needed for type trees such as:
             * type A = [<<b>>]
             */
            case tpe: TypeTree
                if tpe.original != null && sought(tpe.original.symbol) &&
                  tpe.pos.isRange =>
              acc + collect(
                tpe,
                typePos(tpe)
              )
            /**
             * All select statements such as:
             * val a = hello.<<b>>
             */
            case sel: Select
                if soughtOrOverride(sel.symbol) && sel.pos.isRange =>
              traverse(
                acc + collect(
                  sel,
                  sel.namePos
                ),
                sel.qualifier
              )
            /* all definitions:
             * def <<foo>> = ???
             * class <<Foo>> = ???
             * etc.
             */
            case df: MemberDef
                if df.pos.isRange &&
                  (soughtOrOverride(df.symbol) ||
                    isForComprehensionOwner(df)) =>
              (annotationChildren(df) ++ df.children).foldLeft(
                acc + collect(
                  df,
                  df.namePos
                )
              )(traverse(_, _))
            /* Named parameters, since they don't show up in typed tree:
             * foo(<<name>> = "abc")
             * User(<<name>> = "abc")
             * etc.
             */
            case appl: Apply
                if owners(appl.symbol) ||
                  symbolAlternatives(appl.symbol.owner).exists(owners(_)) =>
              val named = appl.args
                .flatMap { arg =>
                  namedArgCache.get(arg.pos.start)
                }
                .collectFirst {
                  case AssignOrNamedArg(i @ Ident(name), _)
                      if (sought.exists(sym => sym.name == name)) =>
                    collect(
                      i,
                      i.pos
                    )
                }
              tree.children.foldLeft(acc ++ named)(traverse(_, _))

            /**
             * We don't automatically traverser types like:
             * val opt: Option[<<String>>] =
             */
            case tpe: TypeTree if tpe.original != null =>
              tpe.original.children.foldLeft(acc)(traverse(_, _))
            /**
             * Some type trees don't have symbols attached such as:
             * type A = List[_ <: <<Iterable>>[Int]]
             */
            case id: Ident
                if id.symbol == NoSymbol && soughtNames.exists(_ == id.name) =>
              fallbackSymbol(id.name, id.pos) match {
                case Some(sym) if sought(sym) =>
                  acc + collect(
                    id,
                    id.pos
                  )
                case _ => acc
              }

            /**
             * For traversing annotations:
             * @<<JsonNotification>>("")
             * def params() = ???
             */
            case df: MemberDef =>
              (tree.children ++ annotationChildren(df))
                .foldLeft(acc)(traverse(_, _))

            /**
             * For traversing import selectors:
             * import scala.util.<<Try>>
             */
            case imp: Import
                if owners(imp.expr.symbol) && imp.selectors
                  .exists(sel => soughtNames(sel.name)) =>
              imp.selectors.foldLeft(traverse(acc, imp.expr)) {
                case (acc, sel) if soughtNames(sel.name) =>
                  val positions =
                    if (!sel.rename.isEmpty)
                      Set(
                        sel.renamePosition(pos.source),
                        sel.namePosition(pos.source)
                      )
                    else Set(sel.namePosition(pos.source))
                  acc ++ positions.map(pos => collect(imp, pos))
                case (acc, _) =>
                  acc
              }
            // catch all missed named trees
            case name: NameTree if sought(name.symbol) && name.pos.isRange =>
              tree.children.foldLeft(
                acc + collect(
                  name,
                  name.namePos
                )
              )(traverse(_, _))

            // needed for `classOf[<<ABC>>]`
            case lit @ Literal(Constant(TypeRef(_, sym, _))) =>
              val posStart = text.indexOfSlice(sym.decodedName, lit.pos.start)
              acc + collect(
                lit,
                lit.pos
                  .withStart(posStart)
                  .withEnd(posStart + sym.decodedName.length())
              )

            case _ =>
              tree.children.foldLeft(acc)(traverse(_, _))
          }
        }
        val all = traverse(Set.empty[T], unit.lastBody)
        all.toList.distinct
      case None => Nil
    }
  }

  private def annotationChildren(mdef: MemberDef): List[Tree] = {
    mdef.mods.annotations match {
      case Nil if mdef.symbol != null =>
        // After typechecking, annotations are moved from the modifiers
        // to the annotation on the symbol of the annotatee.
        mdef.symbol.annotations.map(_.original)
      case anns => anns
    }
  }

  private def typePos(tpe: TypeTree) = {
    tpe.original match {
      case AppliedTypeTree(tpt, _) => tpt.pos
      case sel: NameTree => sel.namePos
      case _ => tpe.pos
    }
  }

}
