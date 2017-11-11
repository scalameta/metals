package scala.meta.languageserver.ctags

import com.github.javaparser.ast
import scala.meta._
import scala.meta.languageserver.Positions
import com.github.javaparser.{Position => JPosition}
import com.github.javaparser.JavaParser
import com.github.javaparser.ast.visitor.VoidVisitorAdapter

object JavaCtags {
  def index(input: Input.VirtualFile): CtagsIndexer = {
    def getPosition(t: ast.Node): Position.Range =
      getPositionOption(t).getOrElse(Position.Range(input, -1, -1))
    def getPositionOption(t: ast.Node): Option[Position.Range] =
      for {
        start <- Option(t.getBegin.orElse(null))
        jend <- Option(t.getEnd.orElse(null))
        end: JPosition = t match {
          // Give names range positions.
          case n: ast.expr.Name =>
            new JPosition(start.line, start.column + n.asString().length)
          case n: ast.expr.SimpleName =>
            new JPosition(start.line, start.column + n.asString().length)
          case _ => jend
        }
      } yield {
        val startOffset =
          Positions.positionToOffset(input, start.line - 1, start.column - 1)
        val endOffset =
          Positions.positionToOffset(input, end.line - 1, end.column - 1)
        Position.Range(input, startOffset, endOffset)
      }
    val cu: ast.CompilationUnit = JavaParser.parse(input.value)
    new VoidVisitorAdapter[Unit] with CtagsIndexer {
      override def language: String = "Java"
      override def visit(
          t: ast.PackageDeclaration,
          ignore: Unit
      ): Unit = {
        val pos = getPosition(t.getName)
        def loop(name: ast.expr.Name): Unit =
          Option(name.getQualifier.orElse(null)) match {
            case None =>
              term(name.getIdentifier, pos, PACKAGE)
            case Some(qual) =>
              loop(qual)
              term(name.getIdentifier, pos, PACKAGE)
          }
        loop(t.getName)
        super.visit(t, ignore)
      }
      override def visit(
          t: ast.body.ClassOrInterfaceDeclaration,
          ignore: Unit
      ): Unit = {
        val name = t.getName.asString()
        val pos = getPosition(t.getName)
        withOwner {
          // TODO(olafur) handle static methods/terms
          if (t.isInterface) tpe(name, pos, TRAIT)
          else tpe(name, pos, CLASS)
          super.visit(t, ignore)
        }
      }
      override def visit(
          t: ast.body.MethodDeclaration,
          ignore: Unit
      ): Unit = {
        term(t.getNameAsString, getPosition(t.getName), DEF)
      }
      override def indexRoot(): Unit = visit(cu, ())
    }
  }
}
