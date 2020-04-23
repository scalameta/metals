package scala.meta.internal.pc

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

  def typeDefinition(params: OffsetParams): List[l.Location] = {
    typeSymbol(params)
      .fold[List[l.Location]](Nil)(
        getSymbolDefinition
      )
  }

  private def typeSymbol(params: OffsetParams): Option[Symbol] = {
    if (params.isWhitespace | params.isDelimiter)
      None
    else {
      val (_, pos, tree) = createCompilationUnit(params)
      Some(tree)
        .filterNot(pointsToIgnored(params))
        .flatMap {
          case sel: Select
              if sel.symbol.isMethod && sel.symbol.asMethod.returnType.typeSymbol.isTypeParameter =>
            //todo remove it; is this case possible at all?
            pprint.log(
              sel.symbol,
              "select is method. ok, it really works sometimes"
            )
            Some(sel.tpe.typeSymbol)
          case app @ Apply(fun, args)
              if !fun.pos.includes(pos) && args.nonEmpty =>
            //most probably, named parameter
            //and if not - those are cases of no interest
            getNamedParameter(app, pos.start)
              .map(_.tpe.typeSymbol)
          case tree if tree.symbol.isDefined && tree.symbol.isMethod =>
            Some(tree.symbol.asMethod.returnType.typeSymbol)
          case tree if tree.symbol.isDefined && tree.symbol.isTypeSymbol =>
            Some(tree.symbol)
          case tree if tree.tpe.isDefined =>
            Some(tree.tpe.typeSymbol)
          case tree
              if tree.children.nonEmpty && tree.children.head.tpe.isDefined =>
            Some(tree.children.head.tpe.typeSymbol)
          case t @ ValDef(_, _, _, rhs) if rhs.isTyped =>
            //todo: remove t
            pprint.log(t.tpe)
            pprint.log(rhs.tpe)
            Some(rhs.tpe.typeSymbol)
          case tree =>
            val expTree = expandRangeToEnclosingApply(tree.pos)
            if (expTree.tpe.isDefined)
              Some(expTree.tpe.typeSymbol)
            else None
        }
    }
  }

  private def getSymbolDefinition(sym: Symbol): List[l.Location] = {
    val file = sym.pos.source.file

    if (file != null && compiler.unitOfFile.contains(file)) {
      val uri =
        if (file.file != null) {
          file.toURL.toURI.toString
        } else {
          sym.pos.source.path
        }

      val unit = compiler.unitOfFile(file)
      unit.body
        .filter(t => t.symbol == sym && t.pos != null && t.isDef)
        .map(t => new l.Location(uri, t.pos.toLSP))
    } else fallbackToSemanticDB(sym)
  }

  private def fallbackToSemanticDB(sym: Symbol): List[l.Location] = {
    val typeSym = semanticdbSymbol(sym)
    search.definition(typeSym).asScala.toList
  }

  private def pointsToIgnored(params: OffsetParams)(t: Tree): Boolean =
    t match {
      //this case is an optimization hack, knowing ignored tags are only valdef keywords
      case vd @ ValDef(_, _, _, _) =>
        val tokens = vd.pos.source.content
          .slice(vd.pos.start, vd.pos.end)
          .mkString
          .tokenize
          .get
          .tokens
        val curToken = tokens
          .dropWhile(_.pos.end < params.offset() - vd.pos.start)
          .head

        ignoredTags.contains(curToken.text)
      case _ => false
    }

  private def getNamedParameter(app: Apply, offset: Int): Option[Symbol] = {
    val rStart = app.fun.pos.start
    val rEnd = app.args.last.pos.end
    val txt = app.pos.source.content
      .slice(rStart, rEnd)
      .mkString

    val tokens = txt.tokenize.get.tokens.toList
    tokens
      .find(t => {
        val tokenStart = app.pos.start + t.pos.start
        val tokenEnd = app.pos.start + t.pos.end
        tokenStart <= offset && tokenEnd > offset
      }) match {
      case Some(t) =>
        app.symbol.asMethod.paramLists.flatten
          .find(_.nameString.trim == t.text)
      case _ =>
        None
    }
  }
}
