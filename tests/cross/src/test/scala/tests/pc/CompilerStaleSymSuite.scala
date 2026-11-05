package tests.pc

import scala.meta.internal.metals.PcQueryContext
import scala.meta.internal.pc.PresentationCompilerConfigImpl
import scala.meta.internal.pc.ScalaPresentationCompiler
import scala.meta.internal.pc.SemanticdbTextDocumentProvider
import scala.meta.internal.semanticdb.Diagnostic
import scala.meta.pc.reports.EmptyReportContext

import munit.TestOptions
import tests.BaseCompletionSuite

class CompilerStaleSymSuite extends BaseCompletionSuite {

  for {
    name <- List("plain", "form-data", "type")
    kind <- List("object", "class")
  } test(s"display-shortening-preserves-compiler-symbol-$kind-$name") {
    val text = s"package sample; object Tokens { $kind `$name` }"
    val compiler = presentationCompiler
      .withConfiguration(PresentationCompilerConfigImpl(emitDiagnostics = true))
      .asInstanceOf[ScalaPresentationCompiler]
      .newCompiler()
    val path = tmp.resolve("Model.scala").toNIO
    try {
      import compiler.{assert => _, _}
      val provider = new SemanticdbTextDocumentProvider(compiler, Nil)
      val model = provider.textDocument(path.toUri, text)
      assertEquals(model.diagnostics, Nil)
      val owner = rootMirror.staticModule("sample.Tokens")
      val selected = owner.info.member(
        if (kind == "object") TermName(name).encode else TypeName(name).encode
      )
      assert(selected != NoSymbol)
      val originalName = selected.name
      implicit val query: PcQueryContext =
        PcQueryContext(None, () => "")(new EmptyReportContext())
      val tpe =
        if (kind == "object") singleType(owner.tpe, selected)
        else typeRef(owner.tpe, selected, Nil)
      for (_ <- 0 until 2) {
        val rendered = shortType(tpe, new ShortenedNames()).toString
        assert(rendered.contains(name))
      }
      val caller = path.resolveSibling("Caller.scala")
      val value =
        if (kind == "object") s"Tokens.`$name`" else s"new Tokens.`$name`"
      val document = provider.textDocument(
        caller.toUri,
        s"package sample; object Caller { val token = $value }"
      )
      assertEquals(
        document.diagnostics.filter(_.severity == Diagnostic.Severity.ERROR),
        Nil
      )
      assertEquals(selected.name, originalName)
    } finally compiler.askShutdown()
  }

  test("display-shortening-preserves-escaped-existential-binding") {
    val text =
      """package sample
        |object Tokens {
        |  def pair: (`odd-name`, `odd-name`) forSome { type `odd-name` <: CharSequence } = ???
        |}
        |""".stripMargin
    val compiler = presentationCompiler
      .withConfiguration(PresentationCompilerConfigImpl(emitDiagnostics = true))
      .asInstanceOf[ScalaPresentationCompiler]
      .newCompiler()
    val path = tmp.resolve("Model.scala").toNIO
    try {
      import compiler.{assert => _, _}
      val provider = new SemanticdbTextDocumentProvider(compiler, Nil)
      val document = provider.textDocument(path.toUri, text)
      assertEquals(
        document.diagnostics.filter(_.severity == Diagnostic.Severity.ERROR),
        Nil
      )
      val owner = rootMirror.staticModule("sample.Tokens")
      val resultType = owner.info.member(TermName("pair")).info.finalResultType
      val quantified = resultType match {
        case ExistentialType(parameters, _) => parameters
        case _ => fail("expected an existential result type")
      }
      val originalNames = quantified.map(_.name)
      val originalBounds = quantified.map(_.info)
      implicit val query: PcQueryContext =
        PcQueryContext(None, () => "")(new EmptyReportContext())
      val rendered = shortType(resultType, new ShortenedNames()).toString
      assertEquals(
        rendered,
        "(`odd-name`, `odd-name`) forSome { type `odd-name` <: CharSequence }"
      )
      assertEquals(quantified.map(_.name), originalNames)
      assertEquals(quantified.map(_.info), originalBounds)
    } finally compiler.askShutdown()
  }

  checkRenamedTopelevel(
    "multiple-requests",
    "Metals",
    (contents, max) => contents.replace(s"object Metals", s"object Metals$max"),
    s"""|B example
        |Metals123456789 example
        |""".stripMargin
  )

  checkRenamedTopelevel(
    "multiple-requests-backtick",
    "`M Metals M`",
    (contents, max) => contents.replace(s"Metals M", s"Metals M "),
    s"""|B example
        |`M Metals M         ` example
        |""".stripMargin
  )

  def checkRenamedTopelevel(
      name: TestOptions,
      toRename: String,
      renameFunc: (String, Int) => String,
      expected: String
  ): Unit = test(name) {
    val file1 = "A.scala"
    val file2 = "B.scala"
    val baseFile = s"""|package example
                       |object $toRename {
                       |  val x = 1
                       |  x@@
                       |}""".stripMargin
    def loop(fileContents: String, max: Int = 9): Unit = {
      getItems(
        fileContents,
        file1,
        restart = false
      )

      if (max > 0) {
        loop(
          renameFunc(fileContents, max),
          max - 1
        )
      }

    }

    loop(baseFile)

    val items = getItems(
      s"""|package example
          |object B {
          |  val x = 1
          |  example.@@
          |}""".stripMargin,
      file2,
      restart = false
    )
    assertNoDiff(
      items.map(_.getLabel).mkString("\n"),
      expected
    )

  }

}
