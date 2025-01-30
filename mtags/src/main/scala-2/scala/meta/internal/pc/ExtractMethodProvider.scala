package scala.meta.internal.pc

import scala.meta.internal.metals.PcQueryContext
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.pc.OffsetParams
import scala.meta.pc.RangeParams

import org.eclipse.{lsp4j => l}

final class ExtractMethodProvider(
    val compiler: MetalsGlobal,
    range: RangeParams,
    extractionPos: OffsetParams
)(implicit queryInfo: PcQueryContext)
    extends ExtractMethodUtils {
  import compiler._
  def extractMethod: List[l.TextEdit] = {
    val text = range.text()
    val unit = addCompilationUnit(
      code = text,
      filename = range.uri.toString(),
      cursor = None
    )
    val pos = unit.position(range.offset)
    typedTreeAt(pos)
    val context = doLocateImportContext(pos)
    val re: scala.collection.Map[Symbol, Name] = renamedSymbols(context)
    val history = new ShortenedNames(
      lookupSymbol = name =>
        context.lookupSymbol(name, sym => !sym.isStale) :: Nil,
      config = renameConfig,
      renames = re
    )
    val scopeSymbols =
      metalsScopeMembers(pos).map(_.sym).filter(_.pos.isDefined).toSet

    def prettyType(tpe: Type, forceAsMethod: Boolean = false): String = {
      def prettyResType(tpe: Type) =
        metalsToLongString(tpe.widen.finalResultType, history)
      def printParamList(params: List[Symbol]): String =
        params match {
          case sym :: Nil => prettyResType(sym.info)
          case _ =>
            s"(${params.map(sym => prettyResType(sym.info)).mkString(", ")})"
        }
      val paramss = tpe.paramss
      if (paramss.nonEmpty)
        s"${paramss.map(printParamList).mkString(" => ")} => ${prettyResType(tpe)}"
      else if (forceAsMethod) s"=> ${prettyResType(tpe)}"
      else prettyResType(tpe)
    }

    def extractFromBlock(t: Tree): List[Tree] =
      t match {
        case Block(stats, expr) =>
          (stats :+ expr).filter(stat => range.encloses(stat.pos))
        case temp: Template =>
          temp.body.filter(stat => range.encloses(stat.pos))
        case other => List(other)
      }

    def localRefs(
        ts: List[Tree],
        defnPos: Position,
        extractedPos: Position
    ) = {
      def nonAvailable(sym: Symbol): Boolean = {
        val symPos = sym.pos
        symPos.isDefined && defnPos.encloses(symPos) && !extractedPos.encloses(
          symPos
        )
      }
      def symFromIdent(id: Ident): Set[Symbol] = {
        val sym = id.symbol match {
          case _: NoSymbol =>
            context.lookupSymbol(
              id.name,
              s =>
                s.isTerm || s.isTypeParameterOrSkolem // skolem is a type parameter viewed from inside its scopes
            ) match {
              case LookupSucceeded(_, symbol) =>
                Set(symbol)
              case _ => Set.empty[Symbol]
            }
          case _ =>
            Set(id.symbol).filter(s => s.isTerm || s.isTypeParameterOrSkolem)
        }
        sym.filter(nonAvailable(_))
      }

      def traverse(symbols: Set[Symbol], tree: Tree): Set[Symbol] =
        tree match {
          case id: Ident =>
            symbols ++ symFromIdent(id)
          case _ =>
            tree.children.foldLeft(symbols)(traverse(_, _))
        }
      val allSymbols = ts
        .foldLeft(Set.empty[Symbol])(traverse(_, _))

      val methodParams = allSymbols.toList.filter(_.isTerm)
      val methodParamTypes = methodParams
        .flatMap(p => p :: p.info.paramss.flatten)
        .map(_.info.typeSymbol)
        .filter(tp => nonAvailable(tp) && tp.isTypeParameterOrSkolem)
        .distinct

      // Type parameter can be a type of one of the parameters or a type parameter in extracted code
      val typeParams =
        allSymbols.filter(_.isTypeParameterOrSkolem) ++ methodParamTypes

      (
        methodParams.sortBy(_.decodedName),
        typeParams.toList.sortBy(_.decodedName)
      )
    }

    def paramsToText(params: List[Symbol]) =
      params
        .map(sym =>
          s"${sym.decodedName}: ${prettyType(sym.info, sym.isMethod && !sym.isGetter)}"
        )
        .mkString(", ")

    val path = compiler.lastVisitedParentTrees
    val edits =
      for {
        enclosing <- path.find(src => src.pos.encloses(range))
        extracted = extractFromBlock(enclosing)
        head <- extracted.headOption
        expr <- extracted.lastOption
        shortenedPath =
          path.takeWhile(src => extractionPos.offset() <= src.pos.start)
        stat = shortenedPath.lastOption.getOrElse(head)
      } yield {
        val defnPos = stat.pos
        val extractedPos = head.pos.withEnd(expr.pos.end)
        val (methodParams, typeParams) =
          localRefs(extracted, defnPos, extractedPos)
        val methodParamsText = paramsToText(methodParams)
        val typeParamsText = typeParams
          .map(_.decodedName) match {
          case Nil => ""
          case params => params.mkString("[", ", ", "]")
        }
        val newExpr = typedTreeAt(expr.pos)
        val (implicitArgs, tpe) = {
          newExpr.tpe match {
            case MethodType(params, resultType)
                if params.nonEmpty && params.forall(_.isImplicit) =>
              (params, resultType.widen)
            case tpe => (Nil, tpe)
          }
        }
        val exprType = if (tpe != null) s": ${prettyType(tpe)}" else ""
        val name = genName(scopeSymbols.map(_.decodedName), "newMethod")
        val exprParams = methodParams.map(_.decodedName).mkString(", ")
        val indent = defnPos.column - (defnPos.point - defnPos.start) - 1
        val blank = text(defnPos.start - indent).toString()
        val newIndent = blank * indent
        val oldIndentLen =
          head.pos.column - (head.pos.point - head.pos.start) - 1
        val toExtract = textToExtract(
          text,
          head.pos.start,
          expr.pos.end,
          newIndent,
          oldIndentLen
        )
        val implicitArgsText =
          if (implicitArgs.nonEmpty) s"(implicit ${paramsToText(implicitArgs)})"
          else ""
        val defText =
          if (extracted.length > 1)
            s"def $name$typeParamsText($methodParamsText)$implicitArgsText$exprType = {\n${toExtract}\n${newIndent}}\n$newIndent"
          else
            s"def $name$typeParamsText($methodParamsText)$implicitArgsText$exprType =\n${toExtract}\n\n$newIndent"
        val replacedText = s"$name($exprParams)"
        List(
          new l.TextEdit(
            extractedPos.toLsp,
            replacedText
          ),
          new l.TextEdit(
            defnPos.focusStart.toLsp,
            defText
          )
        )
      }
    edits.getOrElse(Nil)
  }
}
