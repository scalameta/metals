package scala.meta.internal.pc

import scala.reflect.internal.util.RangePosition

import scala.meta.pc.OffsetParams
import scala.meta.pc.VirtualFileParams

trait PcCollector[T] { self: WithCompilationUnit =>
  import compiler._

  protected def allowZeroExtentImplicits = false

  def collect(
      parent: Option[Tree]
  )(tree: Tree, pos: Position, sym: Option[Symbol]): T

  def resultWithSought(soughtSet: Set[Symbol]): List[T] = {
    val sought = new SoughtSymbols(soughtSet, semanticdbSymbol)
    val owners = soughtSet
      .map(_.owner)
      .flatMap(o => symbolAlternatives(o))
      .filter(_ != NoSymbol)
    val soughtNames: Set[Name] = soughtSet.map(_.name)
    /*
     * For comprehensions have two owners, one for the enumerators and one for
     * yield. This is a heuristic to find that out.
     */
    def isForComprehensionOwner(named: NameTree) = {
      if (named.symbol.pos.isDefined) {
        def alternativeSymbol = soughtSet.exists(symbol =>
          symbol.name == named.name &&
            symbol.pos.isDefined &&
            symbol.pos.start == named.symbol.pos.start
        )
        def sameOwner = {
          val owner = named.symbol.owner
          owner.isAnonymousFunction && owners.exists(o =>
            pos.isDefined && o.pos.isDefined && o.pos.point == owner.pos.point
          )
        }
        alternativeSymbol && sameOwner
      } else false
    }

    def soughtOrOverride(sym: Symbol) =
      sought(sym) || sym.allOverriddenSymbols.exists(sought(_))

    def primaryContructorParent(sym: Symbol) =
      soughtSet
        .flatMap(sym => if (sym.isPrimaryConstructor) Some(sym.owner) else None)
        .contains(sym)

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
          isForComprehensionOwner(df) ||
          primaryContructorParent(df.symbol))
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
      soughtSet.exists(f)
    }

    traverseSought(soughtTreeFilter, soughtFilter).toList
  }

  def resultAllOccurences(): Set[T] = {
    def noTreeFilter = (_: Tree) => true
    def noSoughtFilter = (_: (Symbol => Boolean)) => true

    traverseSought(noTreeFilter, noSoughtFilter)
  }

  def isCorrectPos(t: Tree): Boolean =
    t.pos.isRange || (t.pos.isDefined && allowZeroExtentImplicits && t.symbol.isImplicit)

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
        case ident: Ident if isCorrectPos(ident) && filter(ident) =>
          if (ident.symbol == NoSymbol)
            acc + collect(ident, ident.pos, fallbackSymbol(ident.name, pos))
          else
            acc + collect(ident, ident.pos)

        /**
         * Needed for type trees such as:
         * type A = [<<b>>]
         */
        case tpe: TypeTree
            if isCorrectPos(tpe) && tpe.original != null && filter(tpe) =>
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
        case sel: Select if isCorrectPos(sel) && filter(sel) =>
          val newAcc =
            if (isForComprehensionMethod(sel)) acc
            else acc + collect(sel, sel.namePosition)
          traverse(
            newAcc,
            sel.qualifier
          )

        /**
         * Anonynous function parameters such as:
         * List(1).map{ <<abc>>: Int => abc}
         * In this case, parameter has incorrect namePosition, so we need to handle it separately
         */
        case Function(params, body) =>
          val newAcc = params
            .filter(vd => isCorrectPos(vd) && filter(vd))
            .foldLeft(
              acc
            ) { case (acc, vd) =>
              val pos = vd.pos.withEnd(vd.pos.start + vd.name.length)
              annotationChildren(vd).foldLeft({
                acc + collect(
                  vd,
                  pos
                )
              })(traverse(_, _))
            }
          traverse(
            params.map(_.tpt).foldLeft(newAcc)(traverse(_, _)),
            body
          )
        /* all definitions:
         * def <<foo>> = ???
         * class <<Foo>> = ???
         * etc.
         */
        case df: MemberDef
            if isCorrectPos(df) && filter(
              df
            ) && !df.symbol.isPrimaryConstructor =>
          (annotationChildren(df) ++ df.children).foldLeft({
            val maybeConstructor =
              Some(df.symbol.primaryConstructor).filter(_ != NoSymbol)
            val collected = collect(df, df.namePosition)
            // we also collect primary constructor on class,
            // since primary constructor is synthetic and doesn't have needed span anymore
            // later we cannot get the correct
            val collectedConstructor = maybeConstructor.map(sym =>
              collect(df, df.namePosition, Some(sym))
            )

            acc + collected ++ collectedConstructor
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
          traverse(acc, tpe.original)
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
            if soughtFilter(_ == name.symbol) && isCorrectPos(name) =>
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
    traverseWithParent(None)(Set.empty[T], unit.lastBody)
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

}

abstract class SimpleCollector[T](
    compiler: MetalsGlobal,
    params: VirtualFileParams
) extends WithCompilationUnit(compiler, params)
    with PcCollector[T] {
  def result(): List[T] = resultAllOccurences().toList
}

abstract class WithSymbolSearchCollector[T](
    compiler: MetalsGlobal,
    params: OffsetParams
) extends WithCompilationUnit(compiler, params)
    with PcCollector[T]
    with PcSymbolSearch {
  def result(): List[T] = soughtSymbols
    .map { case (sought, _) => resultWithSought(sought) }
    .getOrElse(Nil)
}

class SoughtSymbols[T](val symbols: Set[T], semanticdbSymbol: T => String) {
  val set: Set[String] = symbols.map(semanticdbSymbol)
  def apply(sym: T): Boolean = set(semanticdbSymbol(sym))
}
