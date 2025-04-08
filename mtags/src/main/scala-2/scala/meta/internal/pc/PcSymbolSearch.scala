package scala.meta.internal.pc

import scala.reflect.internal.util.RangePosition

trait PcSymbolSearch { self: WithCompilationUnit =>
  import compiler._

  private val caseClassSynthetics: Set[Name] = Set(nme.apply, nme.copy)

  lazy val typedTree: Tree = locateTree(pos) match {
    // Check actual object if apply is synthetic
    case sel @ Select(qual, name) if name == nme.apply && qual.pos == sel.pos =>
      qual
    case Import(expr, _) if expr.pos.includes(pos) =>
      // imports seem to be marked as transparent
      locateTree(pos, expr, acceptTransparent = true)
    case t => t
  }

  // First identify the symbol we are at, comments identify @@ as current cursor position
  lazy val soughtSymbols: Option[(Set[Symbol], Position)] = typedTree match {
    /* simple identifier:
     * val a = val@@ue + value
     */
    case (block: Block) =>

      block.stats.collectFirst {
        case vd: ValDef if vd.namePos.includes(pos) && !vd.symbol.isSynthetic =>
          (Set(vd.symbol), vd.namePos)
      }
    case (id: Ident) =>
      // might happen in type trees
      // also this doesn't seem to be picked up by semanticdb
      if (id.symbol == NoSymbol)
        fallbackSymbol(id.name, pos)
          .map(sym => (symbolAlternatives(sym), id.pos))
      else {
        Some(symbolAlternatives(id.symbol), id.pos)
      }
    /* Anonynous function parameters such as:
     * List(1).map{ <<abc>>: Int => abc}
     * In this case, parameter has incorrect namePosition, so we need to handle it separately
     */
    case (vd: ValDef) if isAnonFunctionParam(vd) =>
      val namePos = vd.pos.withEnd(vd.pos.start + vd.name.length)
      if (namePos.includes(pos)) Some(symbolAlternatives(vd.symbol), namePos)
      else None

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

  private def isAnonFunctionParam(vd: ValDef): Boolean =
    vd.symbol != null && vd.symbol.owner.isAnonymousFunction && vd.rhs.isEmpty
}
