package scala.meta.internal.pc

import scala.meta.pc.OffsetParams

import org.eclipse.{lsp4j => l}

final class ExtractMethodProvider(
    val compiler: MetalsGlobal,
    params: OffsetParams,
    applRange: Int,
    lv: Int
) {
  import compiler._
  def extractMethod: List[l.TextEdit] = {
    val unit = addCompilationUnit(
      code = params.text(),
      filename = params.uri().toString(),
      cursor = None
    )
    val startPos = unit.position(params.offset())
    val pos = startPos.withEnd(startPos.start + applRange)
    val appl = typedTreeAt(pos)
    val context = doLocateImportContext(pos)
    val re: scala.collection.Map[Symbol, Name] = renamedSymbols(context)

    val history = new ShortenedNames(
      lookupSymbol = name =>
        context.lookupSymbol(name, sym => !sym.isStale) :: Nil,
      config = renameConfig,
      renames = re
    )
    def prettyType(tpe: Type) =
      metalsToLongString(tpe.widen.finalResultType, history)

    def encloses(outer: Position, inner: Position): Boolean =
      outer.start <= inner.start && outer.end >= inner.end

    def pathTo(appl: Tree, tree: Tree): List[Tree] = {
      def loop(tree: Tree): List[Tree] = {
        tree.children
          .filter(_.pos.isDefined)
          .find(t => encloses(t.pos, appl.pos)) match {
          case Some(t) =>
            t :: loop(t)
          case None => Nil
        }
      }
      loop(tree).reverse
    }

    def namesInApply(t: Tree): Set[TermName] = {
      t match {
        case Apply(fun, args) =>
          namesInApply(fun) ++ args.flatMap(namesInApply(_)).toSet
        case TypeApply(fun, args) =>
          namesInApply(fun) ++ args.flatMap(namesInApply(_)).toSet
        case Select(qualifier, name) =>
          Set(name.toTermName) ++ namesInApply(qualifier)
        case Ident(name) => Set(name.toTermName)
        case _ => Set()
      }
    }

    def localVariables(ts: List[Tree]): List[(TermName, String)] = {
      ts.flatMap(t =>
        t match {
          case Block(stats, expr) => localVariables(stats :+ expr)
          case Template(_, _, body) => localVariables(body)
          case ValDef(_, name, tpt, _) => List((name, prettyType(tpt.tpe)))
          case _ => Nil
        }
      )
    }

    def isBlockOrTemplate(t: Tree): Boolean =
      t match {
        case _: Block => true
        case _: Template => true
        case _ => false
      }

    def statsInBlock(t: Tree): List[Tree] =
      t match {
        case Block(stats, expr) => stats :+ expr
        case Template(_, _, body) => body
        case _ => Nil
      }

    def genName(ts: List[Tree]): String = {
      val names = ts
        .flatMap(
          _ match {
            case DefDef(_, name, _, _, _, _) => Some(name.toString())
            case _ => None
          }
        )
        .toSet
      if (!names("newMethod")) "newMethod"
      else {
        scala.collection.immutable
          .Range(0, 10)
          .map(i => s"newMethod$i")
          .find(!names(_))
          .getOrElse("newMethod")
      }
    }

    val edits = {
      val path = pathTo(appl, unit.body)
      val blocks = path.filter(isBlockOrTemplate(_))
      for {
        block <- if (blocks.length >= lv) Some(blocks(lv)) else None
        stats = statsInBlock(block)
        stat <- stats.find(t => encloses(t.pos, appl.pos))
      } yield {
        val namesInAppl = namesInApply(appl)
        val locals = localVariables(
          path.take(path.indexOf(block))
        ).reverse.toMap
        val text = params.text()
        val indent2 = stat.pos.column - (stat.pos.point - stat.pos.start) - 1
        val blank2 =
          if (text(stat.pos.start - indent2) == '\t') "\t"
          else " "
        val withType =
          locals
            .filter { case (key: TermName, _: String) =>
              namesInAppl.contains(key)
            }
            .toList
            .sorted
        val defParams = withType
          .map { case (k, v) => s"$k: $v" }
          .mkString(", ")
        val applType = prettyType(appl.tpe)
        val applParams = withType.map(_._1).mkString(", ")
        val name = genName(stats)
        val defText =
          s"${blank2 * indent2}def $name($defParams): $applType = ${text
              .slice(appl.pos.start, appl.pos.end)}\n"
        val replacedText = s"$name($applParams)"
        val defPos = new l.Position(new Integer(stat.pos.line - 1), 0)
        List(
          new l.TextEdit(
            new l.Range(
              appl.pos.toLSP.getStart(),
              appl.pos.toLSP.getEnd()
            ),
            replacedText
          ),
          new l.TextEdit(
            new l.Range(defPos, defPos),
            defText
          )
        )
      }
    }
    edits.getOrElse(Nil)
  }
}
