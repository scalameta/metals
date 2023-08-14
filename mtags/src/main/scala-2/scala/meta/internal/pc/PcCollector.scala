package scala.meta.internal.pc

import scala.reflect.internal.util.RangePosition

import scala.meta.pc.OffsetParams
import scala.meta.pc.VirtualFileParams

abstract class PcCollector[T](
    val compiler: MetalsGlobal,
    params: VirtualFileParams
) {
  import compiler._

  def collect(
      parent: Option[Tree]
  )(tree: Tree, pos: Position, sym: Option[Symbol]): T
  val unit: RichCompilationUnit = addCompilationUnit(
    code = params.text(),
    filename = params.uri().toString(),
    cursor = None
  )
  val offset: Int = params match {
    case p: OffsetParams => p.offset()
    case _: VirtualFileParams => 0
  }
  val pos: Position = unit.position(offset)
  lazy val text = unit.source.content
  private val caseClassSynthetics: Set[Name] = Set(nme.apply, nme.copy)
  typeCheck(unit)
  lazy val typedTree: Tree = locateTree(pos) match {
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
        if (sym.owner.isMethod) Set(sym) ++ sym.localCompanion(pos)
        else Set(sym, sym.companionModule, sym.companion.moduleClass)
      } else if (sym.isModuleOrModuleClass) {
        if (sym.owner.isMethod) Set(sym) ++ sym.localCompanion(pos)
        else Set(sym, sym.companionClass, sym.moduleClass)
      } else if (sym.isTerm && (sym.owner.isClass || sym.owner.isConstructor)) {
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

  def adjust(
      pos: Position,
      forRename: Boolean = false
  ): (Position, Boolean) = {
    val isBackticked = text(pos.start) == '`' &&
      text(pos.end - 1) == '`' &&
      pos.start != (pos.end - 1) // for one character names, e.g. `c`
    //                                                    start-^^-end
    val isOldNameBackticked = text(pos.start) == '`' &&
      (text(pos.end - 1) != '`' || pos.start == (pos.end - 1)) &&
      text(pos.end + 1) == '`'
    if (isBackticked && forRename)
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
                        applyOwner.localCompanion(pos).getOrElse(NoSymbol)
                      case comp => comp
                    }
                val info = constructorOwner.info
                val constructorParams = info.members
                  .filter(_.isConstructor)
                  .flatMap(_.paramss)
                  .flatten
                  .filter(_.name == id.name)
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
    case (df: DefTree) if df.namePosition.includes(pos) =>
      Some(symbolAlternatives(df.symbol), df.namePosition)
    /* Import selectors:
     * import scala.util.Tr@@y
     */
    case (imp: Import) if imp.pos.includes(pos) =>
      imp.selector(pos).map(sym => (symbolAlternatives(sym), sym.pos))
    /* simple selector:
     * object.val@@ue
     */
    case (sel: NameTree) if sel.namePosition.includes(pos) =>
      Some(symbolAlternatives(sel.symbol), sel.namePosition)

    // needed for classOf[AB@@C]`
    case lit @ Literal(Constant(TypeRef(_, sym, _))) if lit.pos.includes(pos) =>
      val posStart = text.indexOfSlice(sym.decodedName, lit.pos.start)
      if (posStart == -1) None
      else
        Some(
          (
            symbolAlternatives(sym),
            new RangePosition(
              lit.pos.source,
              posStart,
              lit.pos.point,
              posStart + sym.decodedName.length
            )
          )
        )
    case _ =>
      None
  }

  def result(): List[T] = {
    params match {
      case _: OffsetParams => resultWithSought()
      case _ => resultAllOccurences().toList
    }
  }

  def resultWithSought(): List[T] = {
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
            named.symbol.owner.isAnonymousFunction && owners.exists(owner =>
              pos.isDefined && owner.pos.isDefined && owner.pos.point == named.symbol.owner.pos.point
            )

        def soughtOrOverride(sym: Symbol) =
          sought(sym) || sym.allOverriddenSymbols.exists(sought(_))

        def soughtTreeFilter(tree: Tree): Boolean =
          tree match {
            case ident: Ident
                if (soughtOrOverride(ident.symbol) ||
                  isForComprehensionOwner(ident)) =>
              true
            case tpe: TypeTree =>
              sought(tpe.original.symbol)
            case sel: Select =>
              soughtOrOverride(sel.symbol)
            case df: MemberDef =>
              (soughtOrOverride(df.symbol) ||
              isForComprehensionOwner(df))
            case appl: Apply =>
              owners(appl.symbol) ||
              symbolAlternatives(appl.symbol.owner).exists(owners(_))
            case imp: Import =>
              owners(imp.expr.symbol) && imp.selectors
                .exists(sel => soughtNames(sel.name))
            case bind: Bind =>
              (soughtOrOverride(bind.symbol)) ||
              isForComprehensionOwner(bind)
            case _ => false
          }

        // 1. In most cases, we try to compare by Symbol#==.
        // 2. If there is NoSymbol at a node, we check if the identifier there has the same decoded name.
        //    It it does, we look up the symbol at this position using `fallbackSymbol` or `members`,
        //    and again check if this time we got symbol equal by Symbol#==.
        def soughtFilter(f: Symbol => Boolean): Boolean = {
          sought.exists(f)
        }

        traverseSought(soughtTreeFilter, soughtFilter).toList

      case None => Nil
    }
  }

  def resultAllOccurences(): Set[T] = {
    def noTreeFilter = (_: Tree) => true
    def noSoughtFilter = (_: (Symbol => Boolean)) => true

    traverseSought(noTreeFilter, noSoughtFilter)
  }

  def traverseSought(
      filter: Tree => Boolean,
      soughtFilter: (Symbol => Boolean) => Boolean
  ): Set[T] = {
    // Now find all matching symbols in the document, comments identify <<>> as the symbol we are looking for
    def traverseWithParent(parent: Option[Tree])(
        acc: Set[T],
        tree: Tree
    ): Set[T] = {
      val traverse: (Set[T], Tree) => Set[T] = traverseWithParent(
        Some(tree)
      )
      def collect(t: Tree, pos: Position, sym: Option[Symbol] = None): T =
        this.collect(parent)(t, pos, sym)

      tree match {
        /**
         * All indentifiers such as:
         * val a = <<b>>
         */
        case ident: Ident if ident.pos.isRange && filter(ident) =>
          if (ident.symbol == NoSymbol)
            acc + collect(ident, ident.pos, fallbackSymbol(ident.name, pos))
          else
            acc + collect(ident, ident.pos)

        /**
         * Needed for type trees such as:
         * type A = [<<b>>]
         */
        case tpe: TypeTree
            if tpe.pos.isRange && tpe.original != null && filter(tpe) =>
          tpe.original.children.foldLeft(
            acc + collect(
              tpe.original,
              typePos(tpe)
            )
          )(traverse(_, _))

        /**
         * All select statements such as:
         * val a = hello.<<b>>
         */
        case sel: Select if sel.pos.isRange && filter(sel) =>
          val newAcc =
            if (isForComprehensionMethod(sel)) acc
            else acc + collect(sel, sel.namePosition)
          traverse(
            newAcc,
            sel.qualifier
          )
        /* all definitions:
         * def <<foo>> = ???
         * class <<Foo>> = ???
         * etc.
         */
        case df: MemberDef if df.pos.isRange && filter(df) =>
          (annotationChildren(df) ++ df.children).foldLeft({
            val t = collect(
              df,
              df.namePosition
            )
            if (acc(t)) acc else acc + t
          })(traverse(_, _))
        /* Named parameters, since they don't show up in typed tree:
         * foo(<<name>> = "abc")
         * User(<<name>> = "abc")
         * etc.
         */
        case appl: Apply if filter(appl) =>
          val named = appl.args
            .flatMap { arg =>
              namedArgCache.get(arg.pos.start)
            }
            .collect {
              case AssignOrNamedArg(i @ Ident(name), _)
                  if soughtFilter(_.decodedName == name.decoded) =>
                collect(
                  i,
                  i.pos,
                  appl.symbol.paramss.flatten
                    .find(_.name == i.name)
                )
            }
          tree.children.foldLeft(acc ++ named)(traverse(_, _))

        /**
         * Unapply patterns such as:
         * for {
         *   (<<a>>,<<b>>) <- List((1,2))
         * }
         * case <<bar>>: Bar =>
         */
        case bind: Bind if bind.pos.isDefined && filter(bind) =>
          bind.children.foldLeft(
            acc + collect(
              bind,
              bind.namePosition
            )
          )(traverse(_, _))

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
            if id.symbol == NoSymbol &&
              soughtFilter(_.decodedName == id.name.decoded) =>
          fallbackSymbol(id.name, id.pos) match {
            case Some(sym) if soughtFilter(_ == sym) =>
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
        case imp: Import if filter(imp) =>
          val res = imp.selectors.foldLeft(traverse(acc, imp.expr)) {
            case (acc, sel)
                if soughtFilter(_.decodedName == sel.name.decoded) =>
              val positions =
                if (sel.rename != null && !sel.rename.isEmpty)
                  Set(
                    sel.renamePosition(pos.source),
                    sel.namePosition(pos.source)
                  )
                else Set(sel.namePosition(pos.source))
              val symbol = imp.expr.symbol.info.member(sel.name) match {
                // We can get NoSymbol when we import "_" or when the names don't match
                // eg. "@@" doesn't match "$at$at".
                // Then we try to find member based on decodedName
                case ns: NoSymbol =>
                  imp.expr.symbol.info.members
                    .find(_.name.decoded == sel.name.decoded)
                    .getOrElse(ns)
                case sym => sym
              }
              acc ++ positions.map(pos => collect(imp, pos, Option(symbol)))
            case (acc, _) =>
              acc
          }
          res
        // catch all missed named trees
        case name: NameTree
            if soughtFilter(_ == name.symbol) && name.pos.isRange =>
          tree.children.foldLeft(
            acc + collect(
              name,
              name.namePosition
            )
          )(traverse(_, _))

        // needed for `classOf[<<ABC>>]`
        case lit @ Literal(Constant(TypeRef(_, sym, _)))
            if soughtFilter(_ == sym) =>
          val posStart = text.indexOfSlice(sym.decodedName, lit.pos.start)
          if (posStart == -1) acc
          else
            acc + collect(
              lit,
              new RangePosition(
                lit.pos.source,
                posStart,
                lit.pos.point,
                posStart + sym.decodedName.length
              ),
              Option(sym)
            )

        case _ =>
          tree.children.foldLeft(acc)(traverse(_, _))
      }
    }
    val all = traverseWithParent(None)(Set.empty[T], unit.lastBody)
    all
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
      case sel: NameTree => sel.namePosition
      case _ => tpe.pos
    }
  }

  private val forCompMethods =
    Set(nme.map, nme.flatMap, nme.withFilter, nme.foreach)

  // We don't want to collect synthethic `map`, `withFilter`, `foreach` and `flatMap` in for-comprenhensions
  private def isForComprehensionMethod(sel: Select): Boolean = {
    val syntheticName = sel.name match {
      case name: TermName => forCompMethods(name)
      case _ => false
    }
    val wrongSpan = sel.qualifier.pos.includes(sel.namePosition.focusStart)
    syntheticName && wrongSpan
  }

}
