package scala.meta.internal.pc

import java.util.NoSuchElementException
import org.eclipse.{lsp4j => l}
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.pc.OffsetParams
import scala.meta.internal.jdk.CollectionConverters._

class TypeDefinitionProvider(val compiler: MetalsGlobal) {
  import compiler._

  def typedTree(params: OffsetParams): Option[Tree] = {
    if (params.isWhitespace) {
      None
    } else {
      val (unit, pos, tree) = createCompilationUnit(params)
      Some(tree)
    }
  }

  def typeSymbol(params: OffsetParams): Option[Symbol] = {
    typedTree(params).flatMap {
      case tree if tree.symbol.isMethod =>
        Some(tree.symbol.asMethod.returnType.typeSymbol)
      case tree if tree.symbol.isTypeSymbol =>
        Some(tree.symbol)
      case tree if tree.tpe.isDefined =>
        Some(tree.tpe.typeSymbol)
      case tree if tree.children.nonEmpty =>
        Some(tree.children.head.tpe.typeSymbol)
      case vd: compiler.ValDef if vd.rhs.tpe != null =>
        Some(vd.rhs.tpe.typeSymbol)
      case tree =>
        val expTree = expandRangeToEnclosingApply(tree.pos)
        if (expTree.tpe != null && expTree.tpe.isDefined)
          Some(expTree.tpe.typeSymbol)
        else None
      case _ => None
    }
  }

  def typeDefinition(params: OffsetParams): List[l.Location] = {
    typeSymbol(params).map {
      case sym if sym.isMethod && !sym.isAccessor =>
        val mSym = sym.asMethod
        getSymbolDefinition(mSym.returnType.typeSymbol)
      case sym =>
        getSymbolDefinition(sym.tpe.typeSymbol)
      case _ => Nil
    }
  }.getOrElse(Nil)

  private def getSymbolDefinition(sym: Symbol): List[l.Location] = {
    val file = sym.pos.source.file
    val uri = try {
      file.toURL.toURI.toString
    } catch {
      case _: NullPointerException =>
        sym.pos.source.path
    }

    try {
      if (file != null || compiler.unitOfFile.contains(file)) {
        val unit = compiler.unitOfFile(file)
        if (unit != null) {
          val trees = unit.body
            .filter(_.symbol == sym)
            .filter(_.pos != null)
            .filter(_.isDef)
          trees
            .map(t => new l.Location(uri, t.pos.toLSP))
        } else fallbackToSemanticDB(sym)
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
}
