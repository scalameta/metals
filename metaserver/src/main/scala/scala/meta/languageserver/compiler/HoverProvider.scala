package scala.meta.languageserver.compiler

import scala.annotation.tailrec
import scala.tools.nsc.interactive.Global
import scala.reflect.internal.util.Position
import scala.reflect.internal.util.SourceFile
import com.typesafe.scalalogging.LazyLogging
import langserver.messages.Hover
import langserver.types.RawMarkedString

object HoverProvider {
  def empty: Hover = Hover(Nil, None)

  def hover(compiler: Global, position: Position): Hover = {
    val typedTree = compiler.typedTreeAt(position)
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
      case Left(tree) => Some(tree.tpe.widen.toString)
    }

  }

}
