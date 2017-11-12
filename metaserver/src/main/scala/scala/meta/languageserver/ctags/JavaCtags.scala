package scala.meta.languageserver.ctags

import scala.meta._
import scala.meta.languageserver.ScalametaEnrichments._
import com.github.javaparser.JavaParser
import com.github.javaparser.ParseProblemException
import com.github.javaparser.ParseStart.COMPILATION_UNIT
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.Providers
import com.github.javaparser.ast
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.validator.ProblemReporter
import com.github.javaparser.ast.validator.Validator
import com.github.javaparser.ast.visitor.VoidVisitorAdapter
import com.github.javaparser.{Position => JPosition}
import org.langmeta.languageserver.InputEnrichments._

object JavaCtags {
  val parserConfig: ParserConfiguration = {
    val config = new ParserConfiguration
    // disable a few features we don't need, for performance reasons.
    // early measurements with the default parser showed it handles ~10-15k loc/s,
    // which is slow compared to 30-45k loc/s for scalameta.
    config.setAttributeComments(false)
    config.setDoNotConsiderAnnotationsAsNodeStartForCodeAttribution(true)
    config.setValidator(new Validator {
      override def accept(node: Node, problemReporter: ProblemReporter): Unit =
        ()
    })
    config
  }
  def parseJavaCompilationUnit(contents: String): ast.CompilationUnit = {
    val result = new JavaParser(parserConfig)
      .parse(COMPILATION_UNIT, Providers.provider(contents))
    if (result.isSuccessful) result.getResult.get
    else throw new ParseProblemException(result.getProblems)
  }

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
      } yield
        input.toPosition(
          start.line - 1,
          start.column - 1,
          end.line - 1,
          end.column - 1
        )
    val cu: ast.CompilationUnit = parseJavaCompilationUnit(input.value)
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
          t: ast.body.EnumDeclaration,
          ignore: Unit
      ): Unit = withOwner(owner(t.isStatic)) {
        term(t.getName.asString(), getPosition(t.getName), OBJECT)
        super.visit(t, ignore)
      }
      override def visit(
          t: ast.body.EnumConstantDeclaration,
          ignore: Unit
      ): Unit = withOwner() {
        term(t.getName.asString(), getPosition(t.getName), VAL)
        super.visit(t, ignore)
      }
      override def visit(
          t: ast.body.ClassOrInterfaceDeclaration,
          ignore: Unit
      ): Unit = withOwner(owner(t.isStatic)) {
        val name = t.getName.asString()
        val pos = getPosition(t.getName)
        // TODO(olafur) handle static methods/terms
        if (t.isInterface) tpe(name, pos, TRAIT)
        else tpe(name, pos, CLASS)
        super.visit(t, ignore)
      }
      override def visit(
          t: ast.body.FieldDeclaration,
          ignore: Unit
      ): Unit = withOwner(owner(t.isStatic)) {
        val name = t.getVariables.get(0).getName
        val flags = if (t.isFinal) VAL else VAR
        term(name.asString(), getPosition(name), flags)
      }
      override def visit(
          t: ast.body.MethodDeclaration,
          ignore: Unit
      ): Unit = withOwner(owner(t.isStatic)) {
        term(t.getNameAsString, getPosition(t.getName), DEF)
      }
      override def indexRoot(): Unit = visit(cu, ())
    }
  }
}
