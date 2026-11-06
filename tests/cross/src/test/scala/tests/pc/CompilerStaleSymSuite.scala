package tests.pc

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.util.concurrent.TimeUnit

import scala.meta.internal.metals.CompilerVirtualFileParams
import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.internal.metals.PcQueryContext
import scala.meta.internal.pc.ScalaPresentationCompiler
import scala.meta.internal.pc.WithCompilationUnit
import scala.meta.internal.semanticdb.Diagnostic
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.pc.reports.EmptyReportContext

import munit.TestOptions
import tests.BaseCompletionSuite

class CompilerStaleSymSuite extends BaseCompletionSuite {

  for {
    macroAnnotations <- List(false, true)
    if !macroAnnotations || scalaVersion.startsWith("2.13.")
    (name, source, requiresRecovery) <- List(
      ("root", "package scala; abstract class Any", true),
      ("user-any", "package example; class Any", false),
      ("source-error", "object Invalid { val value: MissingType = ??? }", false)
    )
  } test(s"core-type-$name-macro-$macroAnnotations") {
    val base = presentationCompiler.asInstanceOf[ScalaPresentationCompiler]
    val compiler = base.copy(options =
      base.options.filterNot(_ == "-Ymacro-annotations") ++
        (if (macroAnnotations) List("-Ymacro-annotations") else Nil)
    )
    implicit val query: PcQueryContext =
      PcQueryContext(None, () => "")(new EmptyReportContext())
    def document(name: String, text: String): TextDocument =
      TextDocument.parseFrom(
        compiler
          .semanticdbTextDocument(
            CompilerVirtualFileParams(
              tmp.resolve(s"$name.scala").toNIO.toUri,
              text,
              shouldReturnDiagnostics = true
            )
          )
          .get(30, TimeUnit.SECONDS)
      )
    try {
      val before = document("Before", "object Before { val text = 1.toString }")
      assertEquals(
        before.diagnostics.filter(_.severity == Diagnostic.Severity.ERROR),
        Nil
      )
      val previous =
        compiler.compilerAccess.withSharedCompiler(Option.empty[AnyRef])(
          wrapper => Some(wrapper.compiler())
        )
      assert(previous.nonEmpty)
      val path = tmp.resolve("Any.scala").toNIO
      Files.write(path, source.getBytes(UTF_8))
      val params = CompilerVirtualFileParams(path.toUri, source)
      var attempts = 0
      val result = compiler.compilerAccess
        .withInterruptableCompiler("unavailable", EmptyCancelToken) { wrapper =>
          attempts += 1
          // Exercise the typing boundary used by hover and definition with
          // real source, without injecting invalid compiler symbol state.
          val _ = new WithCompilationUnit(wrapper.compiler(), params)
          "typed"
        }
        .get(30, TimeUnit.SECONDS)
      val after = document(
        "After",
        """object After {
          |  def cast(value: AnyRef): String = value.asInstanceOf[String]
          |  def pick(value: String): String = value
          |  def pick(value: Any): Any = value
          |  val result: String = pick("x")
          |}
          |""".stripMargin
      )
      assertEquals(
        after.diagnostics.filter(_.severity == Diagnostic.Severity.ERROR),
        Nil
      )
      assert(after.occurrences.exists(_.symbol == "scala/Any#asInstanceOf()."))
      val current =
        compiler.compilerAccess.withSharedCompiler(Option.empty[AnyRef])(
          wrapper => Some(wrapper.compiler())
        )
      if (requiresRecovery) {
        assertEquals(result, "unavailable")
        assertEquals(attempts, 2)
        assert(current != previous)
      } else {
        assertEquals(result, "typed")
        assertEquals(attempts, 1)
        assertEquals(current, previous)
      }
    } finally compiler.shutdown()
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
