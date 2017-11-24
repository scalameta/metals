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

    val refinedTree = t match {
      case t: ImplDef if t.impl != null => t.impl
      case t: ValOrDefDef if t.tpt != null => t.tpt
      case t: ValOrDefDef if t.rhs != null => t.rhs
      case x => x
    }

    Option(refinedTree.tpe).map(_.toLongString)
  }

}
