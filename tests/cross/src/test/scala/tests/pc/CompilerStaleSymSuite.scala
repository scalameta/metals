package tests.pc

import munit.TestOptions
import tests.BaseCompletionSuite

class CompilerStaleSymSuite extends BaseCompletionSuite {

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
