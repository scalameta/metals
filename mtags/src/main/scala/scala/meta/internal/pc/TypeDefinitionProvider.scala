package scala.meta.internal.pc

import java.util.NoSuchElementException
import org.eclipse.{lsp4j => l}
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.pc.OffsetParams
import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.tokenizers.Api

class TypeDefinitionProvider(val compiler: MetalsGlobal) extends Api {
  import compiler._

  val ignoredTags: List[String] = List(
    "val",
    "var"
  )

  def tree(params: OffsetParams): Option[Tree] =
    if (params.isWhitespace)
      None
    else {
      val (_, _, tree) = createCompilationUnit(params)
      Some(tree)
    }

  def lineAndCharacterToOffset(
      pos: compiler.Position,
      line: Int,
      char: Int
  ): Int =
    pos.source.lineToOffset(line) + char

  def typeSymbol(params: OffsetParams): Option[Symbol] = {
    tree(params)
      .flatMap {
        case vd: ValDef =>
          val (rStart, rEnd) = (vd.pos.start, vd.pos.end)
          val tokens = vd.pos.source.content
            .slice(rStart, rEnd)
            .mkString
            .tokenize
            .get
            .tokens
          val curToken = tokens
            .dropWhile(_.pos.end < params.offset() - vd.pos.start)
            .head

          if (ignoredTags.contains(curToken.text))
            None
          else
            Some(vd)
        case t: Tree => Some(t)
      }
      .flatMap {
        case sel: Select
            if sel.symbol.asMethod.returnType.typeSymbol.isTypeParameter =>
          Some(sel.tpe.typeSymbol)
        case app @ Apply(fun, args) if args.nonEmpty =>
          val (rStart, rEnd) = (fun.pos.start, args.last.pos.end)

          val txt = app.pos.source.content
            .slice(rStart, rEnd)
            .mkString("")
          txt.tokenize.get.tokens.toList
            .find(t => {
              val (pStart, pEnd) =
                (app.pos.start + t.pos.start, app.pos.start + t.pos.end)
              pStart <= params.offset && pEnd >= params.offset
            }) match {
            case Some(t) =>
              app.symbol.asMethod.paramLists.flatten
                .find(_.nameString.trim == t.text)
                .map(_.tpe.typeSymbol)
            case _ => None
          }
        case tree: Tree if tree.symbol.isMethod =>
          Some(tree.symbol.asMethod.returnType.typeSymbol)
        case tree: Tree if tree.symbol.isTypeSymbol =>
          Some(tree.symbol)
        case tree: Tree if tree.tpe.isDefined =>
          Some(tree.tpe.typeSymbol)
        case tree: Tree if tree.children.nonEmpty =>
          Some(tree.children.head.tpe.typeSymbol)
        case vd: compiler.ValDef if vd.rhs.tpe != null =>
          Some(vd.rhs.tpe.typeSymbol)
        case tree: Tree =>
          val expTree = expandRangeToEnclosingApply(tree.pos)
          if (expTree.tpe != null && expTree.tpe.isDefined)
            Some(expTree.tpe.typeSymbol)
          else None
        case _ => None
      }
  }

  def typeDefinition(params: OffsetParams): List[l.Location] = {
    typeSymbol(params)
      .collect {
        case sym => getSymbolDefinition(sym)
      }
      .toList
      .flatten
  }

  private def getSymbolDefinition(sym: Symbol): List[l.Location] = {
    val file = sym.pos.source.file
    val uri =
      try {
        file.toURL.toURI.toString
      } catch {
        case _: NullPointerException =>
          sym.pos.source.path
      }

    try {
      if (file != null || compiler.unitOfFile.contains(file)) {
        val unit = compiler.unitOfFile(file)
        val trees = unit.body
          .filter(_.symbol == sym)
          .filter(_.pos != null)
          .filter(_.isDef)
        trees
          .map(t => new l.Location(uri, t.pos.toLSP))
      } else fallbackToSemanticDB(sym)
    } catch {
      case _: NoSuchElementException =>
        fallbackToSemanticDB(sym)
    }
  }

  private def fallbackToSemanticDB(sym: Symbol): List[l.Location] = {
    if (sym == null) Nil
    else {
      val typeSym = semanticdbSymbol(sym)
      search.definition(typeSym).asScala.toList
    }
  }

  object Xtensions {

    implicit class XtensionPosition(pos: Position) {
      def includes(point: Int): Boolean = pos.start <= point && pos.end >= point
    }

  }
}
