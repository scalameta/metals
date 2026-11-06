package tests.pc

import scala.meta.internal.pc.ScalaPresentationCompiler

import munit.TestOptions
import tests.BaseCompletionSuite

class CompilerStaleSymSuite extends BaseCompletionSuite {

  test("new-source-retains-incomplete-companion-owner") {
    val compiler =
      presentationCompiler.asInstanceOf[ScalaPresentationCompiler].newCompiler()
    try {
      val text =
        "package sample; object Model { final case class Nested(value: Int) }"
      val modelUnit = compiler.addCompilationUnit(
        text,
        tmp.resolve("Model.scala").toNIO.toUri.toString,
        None
      )
      // Source loading enters declarations before completing lazy members.
      compiler.currentRun.compileLate(modelUnit)
      val model = compiler.rootMirror.staticModule("sample.Model")
      val before = compiler.currentRunId
      compiler.addCompilationUnit(
        "object Next { val value = 1 }",
        tmp.resolve("Next.scala").toNIO.toUri.toString,
        None
      )
      assert(compiler.currentRunId > before)
      val nested = model.moduleClass.info.member(compiler.TypeName("Nested"))
      assert(nested != compiler.NoSymbol)
      assert(nested.companionModule != compiler.NoSymbol)
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

    // Register another source between the old declaration and its rename.
    // A new typer run must not lose the old declaration's cleanup information.
    getItems(
      """|package example
         |object Previous {
         |  val x = 1
         |  x@@
         |}""".stripMargin,
      file1,
      restart = true
    )
    getItems(
      """|package example
         |object B {
         |  val x = 1
         |  x@@
         |}""".stripMargin,
      file2,
      restart = false
    )
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
