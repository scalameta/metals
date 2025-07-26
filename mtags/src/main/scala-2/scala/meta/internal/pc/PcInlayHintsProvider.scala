package scala.meta.internal.pc

import scala.annotation.tailrec

import scala.meta.internal.metals.PcQueryContext
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.pc.InlayHintsParams

import org.eclipse.lsp4j.InlayHint
import org.eclipse.lsp4j.InlayHintKind

final class PcInlayHintsProvider(
    protected val compiler: MetalsGlobal,
    val params: InlayHintsParams
)(implicit queryInfo: PcQueryContext) {
  import compiler._
  val unit: RichCompilationUnit = addCompilationUnit(
    code = params.text(),
    filename = params.uri().toString(),
    cursor = None
  )
  lazy val text = unit.source.content
  lazy val textStr = text.mkString

  typeCheck(unit)
  val pos: Position =
    unit.position(params.offset()).withEnd(params.endOffset())

  def treesInRange(): List[Tree] = {
    val tree = locateTree(pos, unit.lastBody, false)
    if (tree.isEmpty) {
      List(unit.lastBody)
    } else if (!tree.pos.isDefined || params.offset() <= tree.pos.start) {
      List(tree)
    } else enclosedChildren(tree, pos)
  }

  def provide(): List[InlayHint] =
    treesInRange()
      .flatMap(tpdTree =>
        traverse(InlayHints.empty(params.uri()), tpdTree, None).result()
      )

  private def adjustPos(pos: Position): Position =
    pos.adjust(text)._1

  def collectDecorations(
      tree: Tree,
      parent: Option[Tree],
      inlayHints: InlayHints
  ): InlayHints = {
    val firstPassHints = (tree, parent) match {
      // XRay hints are not mutually exclusive with other hints, so they must be matched separately
      case XRayModeHint(tpe, pos) if tpe != null =>
        inlayHints.addToBlock(
          adjustPos(pos).toLsp,
          LabelPart(": ") :: toLabelParts(tpe, pos),
          InlayHintKind.Type
        )
      case _ => inlayHints
    }
    tree match {
      case NamedParameters(params) =>
        params.foldLeft(firstPassHints) { case (acc, (prefix, name, pos)) =>
          acc.add(
            adjustPos(pos).focusStart.toLsp,
            LabelPart(name.toString() + " = " + prefix) :: Nil,
            InlayHintKind.Parameter
          )
        }
      case ImplicitConversion(symbol, range) =>
        val adjusted = adjustPos(range)
        firstPassHints
          .add(
            adjusted.focusStart.toLsp,
            labelPart(symbol, symbol.decodedName) :: LabelPart("(") :: Nil,
            InlayHintKind.Parameter
          )
          .add(
            adjusted.focusEnd.toLsp,
            LabelPart(")") :: Nil,
            InlayHintKind.Parameter
          )
      case ImplicitParameters(trees, pos) =>
        firstPassHints.add(
          adjustPos(pos).focusEnd.toLsp,
          ImplicitParameters.partsFromImplicitArgs(trees),
          InlayHintKind.Parameter
        )
      case TypeParameters(tpes, pos) if tpes.forall(_ != null) =>
        val label = tpes.map(toLabelParts(_, pos)).separated("[", ", ", "]")
        firstPassHints.add(
          adjustPos(pos).focusEnd.toLsp,
          label,
          InlayHintKind.Type
        )
      case ByNameParameters(byNameArgs) =>
        byNameArgs.foldLeft(firstPassHints) { case (ih, pos) =>
          ih.add(
            adjustPos(pos.focusStart).toLsp,
            List(LabelPart("=> ")),
            InlayHintKind.Parameter
          )
        }
      case InferredType(tpe, pos) if tpe != null && !tpe.isError =>
        val adjustedPos = adjustPos(pos).focusEnd
        if (firstPassHints.containsDef(adjustedPos.start)) firstPassHints
        else
          firstPassHints
            .add(
              adjustedPos.toLsp,
              LabelPart(": ") :: toLabelParts(tpe.finalResultType, pos),
              InlayHintKind.Type
            )
            .addDefinition(adjustedPos.start)
      case _ => firstPassHints
    }
  }

  def traverse(
      acc: InlayHints,
      tree: Tree,
      parent: Option[Tree]
  ): InlayHints = {
    val inlayHints = collectDecorations(tree, parent, acc)
    tree.children.foldLeft(inlayHints)(traverse(_, _, Some(tree)))
  }

  private def partsFromType(
      tpe: Type,
      usedRenames: Map[Symbol, String]
  ): List[LabelPart] = {
    tpe
      .collect {
        case t: TypeRef if t.sym != NoSymbol =>
          val label = usedRenames.get(t.sym).getOrElse(t.sym.decodedName)
          labelPart(t.sym, label)
        case SingleType(_, sym) if sym != NoSymbol =>
          val label = usedRenames.get(sym).getOrElse(sym.decodedName)
          labelPart(sym, label)
      }
  }

  private def toLabelParts(
      tpe: Type,
      pos: Position
  ): List[LabelPart] = {
    val context: Context = doLocateImportContext(pos)
    val re: scala.collection.Map[Symbol, Name] = renamedSymbols(context)
    val history = new ShortenedNames(
      lookupSymbol = name =>
        context.lookupSymbol(name, sym => !sym.isStale) :: Nil,
      renames = re
    )
    val tpeStr = metalsToLongString(tpe, history)
    val usedRenames = history.getUsedRenames
    val parts = partsFromType(tpe, usedRenames)
    InlayHints.makeLabelParts(parts, tpeStr)
  }

  private def labelPart(symbol: Symbol, label: String) =
    if (symbol.isSynthetic && !symbol.isMethod) {
      LabelPart(label)
    } else if (symbol.pos.source == pos.source) {
      val pos = if (symbol.pos.start != symbol.pos.point) {
        symbol.pos.withStart(symbol.pos.point)
      } else {
        symbol.pos
      }
      LabelPart(label, pos = Some(pos.toLsp.getStart()))
    } else {
      LabelPart(label, symbol = semanticdbSymbol(symbol))
    }

  object NamedParameters {
    def unapply(
        tree: Tree
    ): Option[List[(String, Name, Position)]] = {
      if (params.namedParameters()) {
        tree match {
          case Apply(fun: Select, args) if isNotInterestingApply(fun) =>
            None
          case Apply(TypeApply(fun: Select, _), args)
              if isNotInterestingApply(fun) =>
            None
          case Apply(fun, args)
              if isRealApply(fun) &&
                /* We don't want to show named parameters for unapplies*/
                args.exists(arg => arg.pos.isRange && arg.pos != fun.pos) &&
                /* It's most likely a block argument, even if it's not it's
                   doubtful that anyone wants to see the named parameters */
                !isSingleBlock(args) =>
            val applyParams = fun.tpe.params
            Some(args.zip(applyParams).collect {
              case (arg, param)
                  if !isNamedArg(arg) && !isDefaultArgument(arg) =>
                val prefix =
                  if (param.isByNameParam && params.byNameParameters()) "=> "
                  else ""
                (prefix, param.name, arg.pos)
            })
          case _ => None
        }
      } else None
    }

    private def isDefaultArgument(arg: Tree) = {
      arg.symbol != null && arg.symbol.isDefaultGetter
    }
    private def isNamedArg(arg: Tree) = {
      def loop(i: Int): Boolean = {
        if (text(i) == '=') true
        else if (text(i).isWhitespace)
          loop(i - 1)
        else false
      }
      loop(arg.pos.start - 1)
    }

    private def isNotInterestingApply(sel: Select) =
      isForComprehensionMethod(sel) || syntheticTupleApply(sel) ||
        isInfix(sel, textStr) || sel.symbol.isSetter || sel.symbol.isJavaDefined

    private def isSingleBlock(args: List[Tree]): Boolean =
      args.size == 1 && args.forall {
        case _: Block => true
        case Typed(_: Block, _) => true
        case _ => false
      }

    private def isNotStringContextApply(fun: Tree) =
      fun.symbol.owner != definitions.StringContextClass && !fun.symbol.isMacro

    private def isRealApply(fun: Tree) =
      fun.pos.isRange && fun.symbol != null && !fun.symbol.isImplicit &&
        isNotStringContextApply(fun) && fun.symbol.paramss.nonEmpty
  }

  object ImplicitConversion {
    def unapply(tree: Tree): Option[(Symbol, Position)] = {
      if (params.implicitConversions())
        tree match {
          case Apply(fun, args)
              if isImplicitConversion(fun) && args.exists(_.pos.isRange) =>
            val lastArgPos = args.lastOption.fold(fun.pos)(_.pos)
            Some((fun.symbol, lastArgPos))
          case _ => None
        }
      else None
    }
    private def isImplicitConversion(fun: Tree) =
      fun.pos.isOffset && fun.symbol != null && fun.symbol.isImplicit
  }
  object ImplicitParameters {
    def unapply(tree: Tree): Option[(List[Tree], Position)] = {
      if (params.implicitParameters())
        tree match {
          case implicitApply: ApplyToImplicitArgs if !tree.pos.isOffset =>
            Some(implicitApply.args, tree.pos)
          case _ => None
        }
      else None
    }

    private def reversedLabelPartsFromParams(
        vparams: List[ValDef]
    ): List[LabelPart] = {
      val labels = vparams
        .map(v =>
          List(
            labelPart(v.tpt.symbol, v.tpt.symbol.decodedName),
            LabelPart(s"${v.name}: ")
          )
        )
        .reverse
        .flatten
      if (labels.size == 0) labels
      else {
        val withCommas =
          if (labels.size > 2)
            labels.zipWithIndex.flatMap {
              case (label, index)
                  // add coma between each param except last
                  if index % 2 == 1 && index != labels.size - 1 =>
                Seq(label, LabelPart(", "))
              case (label, _) => Seq(label)

            }
          else labels
        LabelPart(")") +: withCommas :+ LabelPart("(")
      }
    }

    def partsFromImplicitArgs(trees: List[Tree]): List[LabelPart] = {
      def safeLabel(t: Tree, treeLabel: String) =
        if (t.symbol != null) labelPart(t.symbol, t.symbol.decodedName)
        else if (t.tpe != null)
          LabelPart(s"(?$treeLabel: ${t.tpe.toLongString})")
        else LabelPart(s"(?$treeLabel: ???)")

      @tailrec
      def recurseImplicitArgs(
          currentArgs: List[Tree],
          remainingArgsLists: List[List[Tree]],
          parts: List[LabelPart]
      ): List[LabelPart] =
        (currentArgs, remainingArgsLists) match {
          case (Nil, Nil) => parts
          case (Nil, headArgsList :: tailArgsList) =>
            if (headArgsList.isEmpty) {
              recurseImplicitArgs(
                headArgsList,
                tailArgsList,
                LabelPart(")") :: parts
              )
            } else {
              recurseImplicitArgs(
                headArgsList,
                tailArgsList,
                LabelPart(", ") :: LabelPart(")") :: parts
              )
            }
          case (arg :: remainingArgs, remainingArgsLists) =>
            arg match {
              case Block(_, expr) =>
                recurseImplicitArgs(
                  expr :: remainingArgs,
                  remainingArgsLists,
                  parts
                )
              case Apply(fun, _) if isValueOf(fun.symbol) =>
                val label = LabelPart(
                  "new " + nme.valueOf.decoded.capitalize + "(...)"
                )
                if (remainingArgs.isEmpty)
                  recurseImplicitArgs(
                    remainingArgs,
                    remainingArgsLists,
                    label :: parts
                  )
                else
                  recurseImplicitArgs(
                    remainingArgs,
                    remainingArgsLists,
                    LabelPart(", ") :: label :: parts
                  )
              case Apply(fun, args) =>
                val applyLabel = safeLabel(fun, "apply")
                recurseImplicitArgs(
                  args,
                  remainingArgs :: remainingArgsLists,
                  LabelPart("(") :: applyLabel :: parts
                )
              case t @ Function(vparams, body) =>
                val funLabels =
                  if (t.symbol != null)
                    if (t.symbol.isSynthetic)
                      reversedLabelPartsFromParams(vparams)
                    else List(labelPart(t.symbol, t.symbol.decodedName))
                  else safeLabel(t, "function") :: Nil
                recurseImplicitArgs(
                  List(body),
                  remainingArgs :: remainingArgsLists,
                  LabelPart(" => ") :: funLabels ::: parts
                )
              case t if t.isTerm =>
                val termLabel = safeLabel(t, "term")
                if (remainingArgs.isEmpty)
                  recurseImplicitArgs(
                    remainingArgs,
                    remainingArgsLists,
                    termLabel :: parts
                  )
                else
                  recurseImplicitArgs(
                    remainingArgs,
                    remainingArgsLists,
                    LabelPart(", ") :: termLabel :: parts
                  )
              case _ =>
                recurseImplicitArgs(
                  remainingArgs,
                  remainingArgsLists,
                  parts
                )
            }
        }

      (LabelPart(")") :: recurseImplicitArgs(
        trees,
        Nil,
        List(LabelPart("("))
      )).reverse
    }

    private def isValueOf(symbol: Symbol) =
      symbol != null && symbol.safeOwner.decodedName == nme.valueOf.decoded.capitalize
  }

  object TypeParameters {
    def unapply(tree: Tree): Option[(List[Type], Position)] = {
      if (params.typeParameters())
        tree match {
          case TypeApply(sel: Select, _)
              if isForComprehensionMethod(sel) || syntheticTupleApply(sel) ||
                isInfix(sel, textStr) || sel.symbol.name == nme.unapply =>
            None
          case TypeApply(fun, args)
              if args.exists(_.pos.isOffset) && tree.pos.isRange =>
            val pos = fun match {
              case sel: Select if isInfix(sel, textStr) =>
                sel.namePosition
              case _ => fun.pos
            }
            Some(args.map(_.tpe.widen), pos)
          /* Case matching <<.>>> in the following code:
           *
           * class Foo[T](val t: T)
           * val foo = <<new Foo/*[String]*/>>("foo")
           */
          case New(tpt: TypeTree)
              if tpt.pos.isRange && tpt.tpe.typeArgs.nonEmpty =>
            tpt.original match {
              /* orginal (untyped tree) is an AppliedTypeTree
               * if the type parameter is explicitly passed by the user
               */
              case _: AppliedTypeTree => None
              case _ => Some(tpt.tpe.typeArgs.map(_.widen), tpt.pos)
            }
          case _ => None
        }
      else None
    }
  }

  object InferredType {
    def unapply(tree: Tree): Option[(Type, Position)] = {
      if (params.inferredTypes())
        tree match {
          case vd @ ValDef(_, _, tpt, _)
              if hasMissingTypeAnnot(vd, tpt) &&
                !primaryConstructorParam(vd.symbol) &&
                maybeShowUnapply(vd) &&
                !isCompilerGeneratedSymbol(vd.symbol) &&
                !isValDefBind(vd) =>
            Some(vd.symbol.tpe.widen, vd.namePosition)
          case dd @ DefDef(_, _, _, _, tpt, _)
              if hasMissingTypeAnnot(dd, tpt) &&
                !dd.symbol.isConstructor &&
                !dd.symbol.isMutable &&
                !samePosAsOwner(dd.symbol) =>
            Some(dd.symbol.tpe.widen, findTpePos(dd))
          case bb @ Bind(name, Ident(nme.WILDCARD))
              if params.hintsInPatternMatch && name != nme.WILDCARD && name != nme.DEFAULT_CASE =>
            Some(bb.symbol.tpe.widen, bb.namePosition)
          case _ => None
        }
      else None
    }
    private def hasMissingTypeAnnot(tree: MemberDef, tpt: Tree) =
      tree.pos.isRange && tree.namePosition.isRange && tpt.pos.isOffset && tpt.pos.start != 0

    private def primaryConstructorParam(sym: Symbol) =
      sym.safeOwner.isPrimaryConstructor

    private def samePosAsOwner(sym: Symbol) = {
      val owner = sym.safeOwner
      sym.pos == owner.pos
    }

    private def findTpePos(dd: DefDef) = {
      if (dd.rhs.isEmpty) dd.pos
      else {
        val tpeIdx = text.lastIndexWhere(
          c => !c.isWhitespace && c != '=' && c != '{',
          dd.rhs.pos.start - 1
        )
        dd.pos.withEnd(Math.max(dd.namePosition.end, tpeIdx + 1))
      }
    }
    private def isCompilerGeneratedSymbol(sym: Symbol) =
      sym.decodedName.matches("x\\$\\d+")

    private def maybeShowUnapply(vd: ValDef) =
      (!vd.rhs.pos.isDefined && params.hintsInPatternMatch()) ||
        (isNotInUnapply(vd) || isValidUnapply(vd)) &&
        vd.rhs.pos.start > vd.namePosition.end

    private def isNotInUnapply(vd: ValDef) = vd.rhs.pos.isRange

    private def isValidUnapply(vd: ValDef) =
      params.hintsInPatternMatch() && vd.rhs.pos.isOffset

    /* If is left part of val definition bind:
     * val <<t>> @ ... =
     */
    private def isValDefBind(vd: ValDef) = {
      val afterDef = text.drop(vd.namePosition.end)
      val index = indexAfterSpacesAndComments(afterDef)
      index >= 0 && index < afterDef.size && afterDef(index) == '@'
    }
  }

  object ByNameParameters {
    // Extract the positions at which `=>` hints should be inserted
    def unapply(tree: Tree): Option[List[Position]] = {
      if (params.byNameParameters())
        tree match {
          case Apply(sel: Select, _)
              if isForComprehensionMethod(
                sel
              ) || sel.symbol.name == nme.unapply =>
            None
          case Apply(fun, args) if fun.tpe != null =>
            val params = fun.tpe.params

            /* Special handling for a single block argument:
             *
             *     someOption.getOrElse {/*=> */
             *       fallbackCode
             *     }
             *
             * We cannot just match on the `Block` node because the case of a single expression in
             * braces is not represented as a block. Instead, we search for the braces directly in
             * the source code.
             */
            def singleArgBlockCase = (args, params) match {
              case (Seq(_), Seq(param)) if param.isByNameParam =>
                byNamePosForBlockLike(tree.pos.withStart(fun.pos.end))
                  .map(List(_))
              case _ => None
            }

            /* For all other cases, we just insert the label before the argument tree:
             *
             *     someOption.getOrElse(/*=> */fallbackCode)
             */
            Some(singleArgBlockCase.getOrElse {
              args
                .zip(params)
                .collect { case (tree, param) if param.isByNameParam => tree }
                .map(tree => tree.pos)
                .filter(_.isRange) // filter out default arguments
            })
          case _ => None
        }
      else None
    }

    // If the position passed in wraps a brace-delimited expression, return the position after the opening brace
    private def byNamePosForBlockLike(pos: Position): Option[Position] = {
      val start = text.indexWhere(!_.isWhitespace, pos.start)
      val end = text.lastIndexWhere(!_.isWhitespace, pos.end - 1)
      if (text.lift(start).contains('{') && text.lift(end).contains('}')) {
        Some(pos.withStart(start + 1))
      } else {
        None
      }
    }
  }

  object XRayModeHint {
    def unapply(trees: (Tree, Option[Tree])): Option[(Type, Position)] = {
      if (params.hintsXRayMode()) {
        val (tree, parent) = trees
        val isParentApply = parent match {
          case Some(_: Apply) => true
          case _ => false
        }
        val isParentOnSameLine = parent match {
          case Some(sel: Select) if isForComprehensionMethod(sel) => false
          case Some(par) if par.pos.line == tree.pos.line => true
          case _ => false
        }
        tree match {
          /*
          anotherTree
           .innerSelect()
           */
          case a @ Apply(inner, _)
              if inner.pos.isRange && !isParentOnSameLine && !isParentApply &&
                endsInSimpleSelect(a) && tree.pos.source.isEndOfLine(
                  tree.pos.end
                ) =>
            Some((a.tpe.finalResultType, tree.pos))
          /*
          innerTree
           .select
           */
          case select @ Select(innerTree, _)
              if innerTree.pos.isRange && !isParentOnSameLine && !isParentApply && tree.pos.source
                .isEndOfLine(tree.pos.end) =>
            Some((select.tpe.finalResultType, tree.pos))
          case _ => None
        }
      } else None
    }

    @tailrec
    private def endsInSimpleSelect(ap: Tree): Boolean =
      ap match {
        case Apply(sel: Select, _) =>
          sel.name != nme.apply && !isInfix(sel, textStr)
        case Apply(TypeApply(sel: Select, _), _) =>
          sel.name != nme.apply && !isInfix(sel, textStr)
        case Apply(innerTree @ Apply(_, _), _) =>
          endsInSimpleSelect(innerTree)
        case _ => false
      }

  }

  private def syntheticTupleApply(sel: Select): Boolean = {
    if (
      sel.tpe != null && compiler.definitions.isTupleType(
        sel.tpe.finalResultType
      )
    ) {
      sel match {
        case Select(tupleClass: Select, _)
            if tupleClass.pos.isRange &&
              tupleClass.name.startsWith("Tuple") =>
          val pos = tupleClass.pos
          !text.slice(pos.start, pos.end).mkString.startsWith("Tuple")
        case _ => true
      }
    } else false
  }

}
