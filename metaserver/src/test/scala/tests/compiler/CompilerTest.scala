package tests.compiler

import scala.meta.languageserver.Compiler
import scala.tools.nsc.interactive.Global
import tests.MegaSuite
import scala.concurrent.ExecutionContext.Implicits.global

object CompilerTest extends MegaSuite {
  test("signatureHelp") {
    val compiler: Global = Compiler.newCompiler("", Nil)
    val TIMEOUT = 100
    // very bad test, we should be calling our wrapper around the compiler
    // instead, see https://github.com/scalameta/language-server/issues/47
    val code =
      """
        |object a {
        |  Predef.assert(xxx)
        |  def xxx = 42
        |}
      """.stripMargin
    val unit = Compiler.addCompilationUnit(compiler, code, "a.scala")
    val cursor = code.indexOf("(")
    val pos = unit.position(cursor)
    val completions = compiler.completionsAt(pos).matchingResults().distinct
    assert(completions.nonEmpty)
    val assertName = completions.head.sym.nameString
    assert(assertName == "assert")
    val assertParams =
      completions.head.sym.asMethod.paramLists.head.head.nameString
    assert(assertParams == "assertion")
  }
}
