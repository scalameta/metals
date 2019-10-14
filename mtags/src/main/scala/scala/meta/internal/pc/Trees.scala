package scala.meta.internal.pc

import scala.meta.pc.OffsetParams

trait Trees { this: MetalsGlobal =>

  lazy val isForName: Set[Name] = Set[Name](
    nme.map,
    nme.withFilter,
    nme.flatMap,
    nme.foreach
  )

  def createCompilationUnit(
      params: OffsetParams
  ): (RichCompilationUnit, Position, Tree) = {
    val unit = addCompilationUnit(
      code = params.text(),
      filename = params.filename(),
      cursor = None
    )
    val pos = unit.position(params.offset())
    val tree = typedHoverTreeAt(pos)
    (unit, pos, tree)
  }

  def expandRangeToEnclosingApply(pos: Position): Tree = {
    def tryTail(enclosing: List[Tree]): Option[Tree] = enclosing match {
      case TreeApply(qual, _) :: tail if qual.pos.includes(pos) =>
        tryTail(tail).orElse(Some(enclosing.head))
      case (_: New) :: (_: Select) :: next =>
        tryTail(next)
      case _ =>
        None
    }

    lastVisistedParentTrees match {
      case head :: tail =>
        tryTail(tail) match {
          case Some(value) =>
            typedTreeAt(value.pos)
          case None =>
            head
        }
      case _ =>
        EmptyTree
    }
  }

  def isForSynthetic(gtree: Tree): Boolean = {
    def isForComprehensionSyntheticName(select: Select): Boolean = {
      select.pos == select.qualifier.pos && isForName(select.name)
    }

    gtree match {
      case Apply(fun, List(_: Function)) => isForSynthetic(fun)
      case TypeApply(fun, _) => isForSynthetic(fun)
      case gtree: Select if isForComprehensionSyntheticName(gtree) => true
      case _ => false
    }
  }

  def typedHoverTreeAt(pos: Position): Tree = {
    val treeWithType = typedTreeAt(pos)
    treeWithType match {
      case Import(qual, se) if qual.pos.includes(pos) =>
        qual.findSubtree(pos)
      case Apply(fun, args)
          if !fun.pos.includes(pos) &&
            !isForSynthetic(treeWithType) =>
        // Looks like a named argument, try the arguments.
        val arg = args.collectFirst {
          case arg if treePos(arg).includes(pos) =>
            arg match {
              case Block(_, expr) if treePos(expr).includes(pos) =>
                // looks like a desugaring of named arguments in different order from definition-site.
                expr
              case a => a
            }
        }
        arg.getOrElse(treeWithType)
      case t => t
    }
  }

}
