package scala.meta.languageserver.providers

import scala.tools.nsc.interactive.Global
import scala.meta.languageserver.compiler.Cursor
import scala.meta.languageserver.compiler.ScalacProvider
import langserver.messages.Hover
import langserver.types.RawMarkedString

object HoverProvider {
  def empty: Hover = Hover(Nil, None)

  def hover(
      compiler: Global,
      cursor: Cursor
  ): Hover = {
    val unit = ScalacProvider.addCompilationUnit(
      global = compiler,
      code = cursor.contents,
      filename = cursor.uri,
      cursor = None
    )
    val pos = unit.position(cursor.offset)
    val typedTree = compiler.typedTreeAt(pos)
    typeOfTree(compiler)(typedTree).fold(empty) { tpeName =>
      Hover(
        contents = List(
          RawMarkedString(language = "scala", value = tpeName)
        ),
        range = None
      )
    }
  }

  private def typeOfTree(c: Global)(t: c.Tree): Option[String] = {
    import c._

    val stringOrTree = t match {
      case t: DefDef => Right(t.symbol.asMethod.info.toLongString)
      case t: ValDef if t.tpt != null => Left(t.tpt)
      case t: ValDef if t.rhs != null => Left(t.rhs)
      case x => Left(x)
    }

    stringOrTree match {
      case Right(string) => Some(string)
      case Left(null) => None
      case Left(tree) if tree.tpe ne NoType => Some(tree.tpe.widen.toString)
      case _ => None
    }

  }

}
