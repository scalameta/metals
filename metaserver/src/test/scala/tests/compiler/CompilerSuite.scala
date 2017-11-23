package tests.compiler

import scala.meta.languageserver.Compiler
import scala.reflect.internal.util.Position
import scala.tools.nsc.interactive.Global
import tests.MegaSuite

class CompilerSuite(implicit file: sourcecode.File) extends MegaSuite {
  val compiler: Global =
    Compiler.newCompiler(classpath = "", scalacOptions = Nil)

  def pretty(position: Position): String =
    s"""${position.lineContent}
       |${position.lineCaret}""".stripMargin
  val TIMEOUT = 100
  private def computeDatabaseAndNamesFromMarkup(
      filename: String,
      markup: String
  ): List[Position] = {
    val chevrons = "<<(.*?)>>".r
    val carets0 =
      chevrons.findAllIn(markup).matchData.map(m => (m.start, m.end)).toList
    val carets = carets0.zipWithIndex.map {
      case ((s, e), i) => (s - 4 * i, e - 4 * i - 4)
    }
    val code = chevrons.replaceAllIn(markup, "$1")
    val unit = Compiler.addCompilationUnit(compiler, code, filename)
    carets.map {
      case (start, end) =>
        unit.position(start)
    }
  }

  trait OverloadHack1; implicit object OverloadHack1 extends OverloadHack1
  trait OverloadHack2; implicit object OverloadHack2 extends OverloadHack2
  trait OverloadHack3; implicit object OverloadHack3 extends OverloadHack3
  trait OverloadHack4; implicit object OverloadHack4 extends OverloadHack4
  trait OverloadHack5; implicit object OverloadHack5 extends OverloadHack5

  def targeted(filename: String, markup: String, fn: Position => Unit)(
      implicit hack: OverloadHack1
  ): Unit = {
    test(filename) {
      val positions =
        computeDatabaseAndNamesFromMarkup(filename + ".scala", markup)
      positions match {
        case List(pos) => fn(pos)
        case _ =>
          sys.error(s"1 chevron expected, ${positions.length} chevrons found")
      }
    }
  }

  def targeted(
      filename: String,
      markup: String,
      fn: (Position, Position) => Unit
  )(implicit hack: OverloadHack2): Unit = {
    test(filename) {
      val positions =
        computeDatabaseAndNamesFromMarkup(filename, markup)
      positions match {
        case pos :: pos2 :: Nil => fn(pos, pos2)
        case _ =>
          sys.error(s"2 chevrons expected, ${positions.length} chevrons found")
      }
    }
  }
}
