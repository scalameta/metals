package tests

import scala.meta.internal.metals.codeactions.CreateNewSymbol
import scala.meta.internal.metals.codeactions.ImportMissingSymbol

class OutlineLspSuite extends BaseNonCompilingLspSuite("outline") {
  override val scalaVersionConfig: String = ""
  override val saveAfterChanges: Boolean = false
  override val scala3Diagnostics = false

  test("imports-for-non-compiling") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "a": {}
           |}
           |/a/src/main/scala/a/A.scala
           |package a
           |
           |/a/src/main/scala/b/B.scala
           |package b
           |
           |object O {
           |  class UniqueObject {
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/b/B.scala")
      _ <- server.didChange("a/src/main/scala/b/B.scala") { _ =>
        """|package b
           |object W {
           |  class UniqueObject {
           |    val i: Int = "aaa"
           |  }
           |}
           |""".stripMargin
      }
      _ <- server.didSave("a/src/main/scala/b/B.scala")(identity)
      // check if the change name is picked up despite the file not compiling
      newText = """|package a
                   |
                   |object A {
                   |  // @@
                   |  val k: <<UniqueObject>> = ???
                   |}
                   |""".stripMargin
      input = newText.replace("<<", "").replace(">>", "")
      _ <- server.didChange("a/src/main/scala/a/A.scala")(_ => input)
      _ <- server.didSave("a/src/main/scala/a/A.scala")(identity)
      codeActions <-
        server
          .assertCodeAction(
            "a/src/main/scala/a/A.scala",
            newText,
            s"""|${ImportMissingSymbol.title("UniqueObject", "b.W")}
                |${CreateNewSymbol.title("UniqueObject")}
                |""".stripMargin,
            kind = Nil,
          )
      _ <- assertCompletionEdit(
        "UniqueObject@@",
        """|package a
           |
           |import b.W.UniqueObject
           |
           |object A {
           |  UniqueObject
           |  val k: UniqueObject = ???
           |}
           |""".stripMargin,
      )
    } yield ()
  }
}
