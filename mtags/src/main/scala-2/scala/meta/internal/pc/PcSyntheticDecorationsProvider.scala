package scala.meta.internal.pc

import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.pc.SyntheticDecoration
import scala.meta.pc.SyntheticDecorationsParams

final class PcSyntheticDecorationsProvider(
    protected val compiler: MetalsGlobal,
    val params: SyntheticDecorationsParams
) {
  import compiler._
  val unit: RichCompilationUnit = addCompilationUnit(
    code = params.text(),
    filename = params.uri().toString(),
    cursor = None
  )
  lazy val text = unit.source.content
  lazy val textStr = text.mkString

  typeCheck(unit)

  def tpdTree = unit.lastBody

  def provide(): List[SyntheticDecoration] =
    traverse(Synthetics.empty, tpdTree).result()

  def collectDecorations(
      tree: Tree,
      decorations: Synthetics
  ): Synthetics =
    tree match {
      case ImplicitConversion(name, pos) if params.implicitConversions() =>
        val adjusted = pos.adjust(text)._1
        decorations
          .add(
            Decoration(
              adjusted.focusStart.toLsp,
              name + "(",
              DecorationKind.ImplicitConversion
            )
          )
          .add(
            Decoration(
              adjusted.focusEnd.toLsp,
              ")",
              DecorationKind.ImplicitConversion
            )
          )
      case ImplicitParameters(names, pos, allImplicit)
          if params.implicitParameters() =>
        val label =
          if (allImplicit) names.mkString("(", ", ", ")")
          else names.mkString(", ", ", ", "")
        val adjusted = pos.adjust(text)._1
        decorations.add(
          Decoration(
            adjusted.focusEnd.toLsp,
            label,
            DecorationKind.ImplicitParameter
          )
        )
      case TypeParameters(types, pos) if params.typeParameters() =>
        val label = types.map(toLabel(_, pos)).mkString("[", ", ", "]")
        val adjusted = pos.adjust(text)._1
        decorations.add(
          Decoration(
            adjusted.focusEnd.toLsp,
            label,
            DecorationKind.TypeParameter
          )
        )
      case InferredType(tpe, pos) if params.inferredTypes() =>
        val label = toLabel(tpe, pos)
        val adjusted = pos.adjust(text)._1
        if (decorations.containsDef(adjusted.end)) decorations
        else
          decorations.add(
            Decoration(
              adjusted.focusEnd.toLsp,
              ": " + label,
              DecorationKind.InferredType
            ),
            adjusted.end
          )
      case _ => decorations
    }

  def traverse(
      acc: Synthetics,
      tree: Tree
  ): Synthetics = {
    val decorations = collectDecorations(tree, acc)
    tree.children.foldLeft(decorations)(traverse(_, _))
  }

  object ImplicitConversion {
    def unapply(tree: Tree): Option[(String, Position)] = tree match {
      case Apply(fun, args)
          if isImplicitConversion(fun) && args.exists(_.pos.isRange) =>
        val lastArgPos = args.lastOption.fold(fun.pos)(_.pos)
        Some((fun.symbol.decodedName, lastArgPos))
      case _ => None
    }
    private def isImplicitConversion(fun: Tree) =
      fun.pos.isOffset && fun.symbol != null && fun.symbol.isImplicit
  }
  object ImplicitParameters {
    def unapply(tree: Tree): Option[(List[String], Position, Boolean)] =
      tree match {
        case Apply(_, args)
            if args.exists(isSyntheticArg) && !tree.pos.isOffset =>
          val (implicitArgs, providedArgs) = args.partition(isSyntheticArg)
          val allImplicit = providedArgs.isEmpty
          val pos = providedArgs.lastOption.fold(tree.pos)(_.pos)
          Some(implicitArgs.map(_.symbol.decodedName), pos, allImplicit)
        case Apply(ta: TypeApply, Apply(fun, _) :: _)
            if fun.pos.isOffset && isValueOf(fun.symbol) =>
          val pos = ta.pos
          Some(
            List("new " + nme.valueOf.decoded.capitalize + "(...)"),
            pos,
            true
          )
        case _ => None
      }
    private def isValueOf(symbol: Symbol) =
      symbol != null && symbol.safeOwner.decodedName == nme.valueOf.decoded.capitalize

    private def isSyntheticArg(arg: Tree): Boolean =
      arg.pos.isOffset && arg.symbol != null && arg.symbol.isImplicit
  }

  object TypeParameters {
    def unapply(tree: Tree): Option[(List[Type], Position)] = tree match {
      case TypeApply(sel: Select, _)
          if isForComprehensionMethod(sel) || syntheticTupleApply(sel) =>
        None
      case TypeApply(fun, args)
          if args.exists(_.pos.isOffset) && tree.pos.isRange =>
        val pos = fun match {
          case sel: Select if isInfix(sel, textStr) =>
            sel.namePosition
          case _ => fun.pos
        }
        Some(args.map(_.tpe.widen.finalResultType), pos)
      case _ => None
    }
  }

  object InferredType {
    def unapply(tree: Tree): Option[(Type, Position)] = tree match {
      case vd @ ValDef(_, _, tpt, _)
          if hasMissingTypeAnnot(vd, tpt) &&
            !primaryConstructorParam(vd.symbol) &&
            isNotInUnapply(vd) &&
            !isCompilerGeneratedSymbol(vd.symbol) &&
            !isValDefBind(vd) =>
        Some(vd.symbol.tpe.widen.finalResultType, vd.namePosition)
      case dd @ DefDef(_, _, _, _, tpt, _)
          if hasMissingTypeAnnot(dd, tpt) &&
            !dd.symbol.isConstructor &&
            !dd.symbol.isMutable &&
            !samePosAsOwner(dd.symbol) =>
        Some(dd.symbol.tpe.widen.finalResultType, findTpePos(dd))
      case bb @ Bind(name, Ident(nme.WILDCARD))
          if name != nme.WILDCARD && name != nme.DEFAULT_CASE =>
        Some(bb.symbol.tpe.widen.finalResultType, bb.namePosition)
      case _ => None
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

    private def isNotInUnapply(vd: ValDef) =
      !vd.rhs.pos.isRange || vd.rhs.pos.start > vd.namePosition.end

    /* If is left part of val definition bind:
     * val <<t>> @ ... =
     */
    private def isValDefBind(vd: ValDef) = {
      val afterDef = text.drop(vd.namePosition.end)
      val index = indexAfterSpacesAndComments(afterDef)
      index >= 0 && index < afterDef.size && afterDef(index) == '@'
    }
  }

  private def syntheticTupleApply(sel: Select): Boolean = {
    if (compiler.definitions.isTupleType(sel.tpe.finalResultType)) {
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
  private def toLabel(
      tpe: Type,
      pos: Position
  ): String = {
    val context: Context = doLocateImportContext(pos)
    val re: scala.collection.Map[Symbol, Name] = renamedSymbols(context)
    val history = new ShortenedNames(
      lookupSymbol = name =>
        context.lookupSymbol(name, sym => !sym.isStale) :: Nil,
      config = renameConfig,
      renames = re
    )
    metalsToLongString(tpe, history)
  }

  case class Synthetics(
      decorations: List[Decoration],
      definitions: Set[Int] = Set.empty
  ) {
    def containsDef(offset: Int): Boolean = definitions(offset)
    def add(decoration: Decoration, offset: Int): Synthetics =
      copy(
        decorations = addDecoration(decoration),
        definitions = definitions + offset
      )
    def add(decoration: Decoration): Synthetics =
      copy(
        decorations = addDecoration(decoration),
        definitions = definitions
      )
    // If method has both type parameter and implicit parameter, we want the type parameter decoration to be displayed first,
    // but it's added second. This method adds the decoration to the right position in the list.
    private def addDecoration(decoration: Decoration): List[Decoration] = {
      val atSamePos =
        decorations.takeWhile(_.range.getStart() == decoration.range.getStart())
      (atSamePos :+ decoration) ++ decorations.drop(atSamePos.size)
    }
    def result(): List[Decoration] = decorations.reverse
  }

  object Synthetics {
    def empty: Synthetics = Synthetics(Nil, Set.empty)
  }
}
