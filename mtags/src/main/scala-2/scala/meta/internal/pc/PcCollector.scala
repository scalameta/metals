package scala.meta.internal.pc

import scala.reflect.internal.util.RangePosition

import scala.meta.pc.OffsetParams
import scala.meta.pc.RangeParams
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
      rhs.pos -> arg
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
    /* named argument, which is a bit complex:
     * foo(nam@@e = "123")
     */
    case _ =>
      val apply = typedTree match {
        case (apply: Apply) => Some(apply)
        /**
         * For methods with multiple parameter lists and default args, the tree looks like this:
         * Block(List(val x&1, val x&2, ...), Apply(<<method>>, List(x&1, x&2, ...)))
         */
        case _ =>
          typedTree.children.collectFirst {
            case Block(_, apply: Apply) if apply.pos.includes(pos) =>
              apply
          }
      }
      apply
        .collect {
          case apply if apply.symbol != null =>
            collectArguments(apply)
              .flatMap(arg => namedArgCache.find(_._1.includes(arg.pos)))
              .collectFirst {
                case (_, AssignOrNamedArg(id: Ident, _))
                    if id.pos.includes(pos) =>
                  apply.symbol.paramss.flatten.find(_.name == id.name).map {
                    s =>
                      // if it's a case class we need to look for parameters also
                      if (
                        caseClassSynthetics(s.owner.name) && s.owner.isSynthetic
                      ) {
                        val applyOwner = s.owner.owner
                        val constructorOwner =
                          if (applyOwner.isCaseClass) applyOwner
                          else
                            applyOwner.companion match {
                              case NoSymbol =>
                                applyOwner
                                  .localCompanion(pos)
                                  .getOrElse(NoSymbol)
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
        }
        .getOrElse(None)
  }

  def treesInRange(rangeParams: RangeParams): List[Tree] = {
    val pos: Position =
      unit.position(rangeParams.offset()).withEnd(rangeParams.endOffset())
    val tree = locateTree(pos, unit.lastBody, false)
    if (tree.isEmpty) {
      List(unit.lastBody)
    } else if (!tree.pos.isDefined || rangeParams.offset() <= tree.pos.start) {
      List(tree)
    } else {
      tree.children
        .filter(c =>
          !c.pos.isDefined ||
            c.pos.start <= rangeParams.endOffset && c.pos.end >= rangeParams
              .offset()
        )
    }
  }

  def result(): List[T] = {
    params match {
      case _: OffsetParams => resultWithSought()
      case _ => resultAllOccurences()(List(unit.lastBody))
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
              appl.symbol != null &&
              (owners(appl.symbol) ||
                symbolAlternatives(appl.symbol.owner).exists(owners(_)))
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

        traverseSought(soughtTreeFilter, soughtFilter)(List(unit.lastBody))

      case None => Nil
    }
  }

  def resultAllOccurences(includeSynthetics: Boolean = false)(
      toTraverse: List[Tree] = List(unit.lastBody)
  ): List[T] = {
    def noTreeFilter = (_: Tree) => true
    def noSoughtFilter = (_: (Symbol => Boolean)) => true

    traverseSought(noTreeFilter, noSoughtFilter, includeSynthetics)(toTraverse)
  }

  def traverseSought(
      filter: Tree => Boolean,
      soughtFilter: (Symbol => Boolean) => Boolean,
      includeSynthetics: Boolean = false
  )(
      toTraverse: List[Tree]
  ): List[T] = {
    def syntheticFilter(tree: Tree) = includeSynthetics &&
      tree.pos.isOffset &&
      tree.symbol.isImplicit
    // Now find all matching symbols in the document, comments identify <<>> as the symbol we are looking for
    def traverseWithParent(parent: Option[Tree])(
        acc: List[T],
        tree: Tree
    ): List[T] = {
      val traverse: (List[T], Tree) => List[T] = traverseWithParent(
        Some(tree)
      )
      def collect(t: Tree, pos: Position, sym: Option[Symbol] = None): T =
        this.collect(parent)(t, pos, sym)

      tree match {
        /**
         * All indentifiers such as:
         * val a = <<b>>
         */
        case ident: Ident
            if ident.pos.isRange && filter(ident) ||
              syntheticFilter(ident) =>
          if (ident.symbol == NoSymbol)
            collect(ident, ident.pos, fallbackSymbol(ident.name, pos)) :: acc
          else
            collect(ident, ident.pos) :: acc

        /**
         * Needed for type trees such as:
         * type A = [<<b>>]
         */
        case tpe: TypeTree
            if tpe.pos.isRange && tpe.original != null && filter(tpe) =>
          tpe.original.children.foldLeft(
            collect(
              tpe.original,
              typePos(tpe)
            ) :: acc
          )(traverse(_, _))

        /**
         * All select statements such as:
         * val a = hello.<<b>>
         */
        case sel: Select if sel.pos.isRange && filter(sel) =>
          val newAcc =
            if (isForComprehensionMethod(sel)) acc
            else collect(sel, sel.namePosition) :: acc
          traverse(
            newAcc,
            sel.qualifier
          )

        case sel: Select if syntheticFilter(sel) =>
          traverse(
            collect(
              sel,
              sel.pos
            ) :: acc,
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
            if (acc.contains(t)) acc else t :: acc
          })(traverse(_, _))
        /* Named parameters, since they don't show up in typed tree:
         * foo(<<name>> = "abc")
         * User(<<name>> = "abc")
         * etc.
         */
        case appl: Apply if filter(appl) =>
          val named = collectArguments(appl)
            .flatMap { arg =>
              namedArgCache.find(_._1.includes(arg.pos))
            }
            .collect {
              case (_, AssignOrNamedArg(i @ Ident(name), _))
                  if soughtFilter(_.decodedName == name.decoded) =>
                val symbol = Option(appl.symbol).flatMap(
                  _.paramss.flatten.find(_.name == i.name)
                )
                collect(
                  i,
                  i.pos,
                  symbol
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
            collect(
              bind,
              bind.namePosition
            ) :: acc
          )(traverse(_, _))

        /**
         * We don't want to collect type parameters in for-comp map, flatMap etc.
         */
        case TypeApply(sel: Select, _) if isForComprehensionMethod(sel) =>
          traverse(acc, sel.qualifier)

        /**
         * We don't automatically traverser types like:
         * val opt: Option[<<String>>] =
         */
        case tpe: TypeTree if tpe.original != null =>
          tpe.original.children.foldLeft(acc)(traverse(_, _))

        /**
         * For collecting synthetic type parameters:
         * def hello[T](t: T) = t
         * val x = hello<<[List[Int]]>>(List<<Int>>(1))
         */
        case tpe: TypeTree if includeSynthetics && tpe.pos.isOffset =>
          collect(
            tpe,
            tpe.pos
          ) :: acc
        /**
         * Some type trees don't have symbols attached such as:
         * type A = List[_ <: <<Iterable>>[Int]]
         */
        case id: Ident
            if id.symbol == NoSymbol &&
              soughtFilter(_.decodedName == id.name.decoded) =>
          fallbackSymbol(id.name, id.pos) match {
            case Some(sym) if soughtFilter(_ == sym) =>
              collect(
                id,
                id.pos
              ) :: acc
            case _ => acc
          }

        /**
         * We don't want to traverse type from synthetic match-case in
         * val <<hd :: tail = List(1,2,3)>>
         */
        case Typed(expr, tpt) if !tpt.pos.isRange =>
          traverse(acc, expr)
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
            collect(
              name,
              name.namePosition
            ) :: acc
          )(traverse(_, _))

        // needed for `classOf[<<ABC>>]`
        case lit @ Literal(Constant(TypeRef(_, sym, _)))
            if soughtFilter(_ == sym) =>
          val posStart = text.indexOfSlice(sym.decodedName, lit.pos.start)
          if (posStart == -1) acc
          else
            collect(
              lit,
              new RangePosition(
                lit.pos.source,
                posStart,
                lit.pos.point,
                posStart + sym.decodedName.length
              ),
              Option(sym)
            ) :: acc

        case _ =>
          tree.children.foldLeft(acc)(traverse(_, _))
      }
    }
    toTraverse.flatMap(traverseWithParent(None)(List.empty[T], _))
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

  private def collectArguments(apply: Apply): List[Tree] = {
    apply.fun match {
      case appl: Apply => collectArguments(appl) ++ apply.args
      case _ => apply.args
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
