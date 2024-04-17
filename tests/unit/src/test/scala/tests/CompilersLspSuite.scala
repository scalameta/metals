package tests

import scala.concurrent.Future

import scala.meta.internal.metals.codeactions.CreateNewSymbol
import scala.meta.internal.metals.codeactions.ImportMissingSymbol

class CompilersLspSuite extends BaseCompletionLspSuite("compilers") {
  test("reset-pc") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """/metals.json
          |{
          |  "a": {},
          |  "b": { "dependsOn": ["a"] }
          |}
          |/a/src/main/scala/a/A.scala
          |package a
          |class A {
          |  // @@
          |  def completeThisUniqueName() = 42
          |}
          |/b/src/main/scala/b/B.scala
          |package b
          |object B {
          |  // @@
          |  def completeThisUniqueName() = 42
          |}
          |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ <- server.didOpen("b/src/main/scala/b/B.scala")
      _ = assertNoDiagnostics()
      _ <- Future.sequence(
        List('a', 'b').map { project =>
          assertCompletion(
            "completeThisUniqueNa@@",
            "completeThisUniqueName(): Int",
            project = project,
          )
        }
      )
      count = server.server.loadedPresentationCompilerCount()
      _ = assertEquals(
        2,
        count,
      )
      _ <-
        server.didSave("b/src/main/scala/b/B.scala")(_ => "package b; object B")
      _ <-
        server.didSave("a/src/main/scala/a/A.scala")(_ => "package a; class A")
      _ = assertNoDiagnostics()
      countAfter = server.server.loadedPresentationCompilerCount()
      _ = assertEquals(
        0,
        countAfter,
      )
    } yield ()
  }

  test("non-compiling") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """/metals.json
          |{
          |  "a": {}
          |}
          |/a/src/main/scala/a/A.scala
          |package a
          |class A {
          |  // @@
          |  def completeThisUniqueName() = 42
          |}
          |/a/src/main/scala/b/B.scala
          |package b
          |object UniqueObject {
          |  def completeThisUniqueName() = 42
          |}
          |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ <- server.didOpen("a/src/main/scala/b/B.scala")
      _ = assertNoDiagnostics()
      // break the file and add a new method, should show methods
      _ <-
        server.didChange("a/src/main/scala/b/B.scala") { _ =>
          """|package b
             |object UniqueObject{
             |  def completeThisUniqueName() = 42
             |  def completeThisUniqueName2(): String = 42
             |}""".stripMargin
        }
      _ <- assertCompletion(
        "b.UniqueObject.completeThisUniqueNa@@",
        """|completeThisUniqueName(): Int
           |completeThisUniqueName2(): String""".stripMargin,
      )
      // make sure autoimports are properly suggested
      _ <- assertCompletionEdit(
        "UniqueObject@@",
        """|package a
           |
           |import b.UniqueObject
           |class A {
           |  UniqueObject
           |  def completeThisUniqueName() = 42
           |}
           |""".stripMargin,
      )
      // change the name of the object and test again
      _ <-
        server.didChange("a/src/main/scala/b/B.scala") { _ =>
          """|package b
             |object UniqueObjectOther{
             |  def completeThisUniqueName() = 42
             |  def completeThisUniqueName2(): String = 42
             |}""".stripMargin
        }
      _ <- assertCompletion(
        "b.UniqueObjectOther.completeThisUniqueNa@@",
        """|completeThisUniqueName(): Int
           |completeThisUniqueName2(): String""".stripMargin,
      )
      // make sure old name is not suggested
      _ <- assertCompletionEdit(
        "UniqueObject@@",
        """|package a
           |
           |import b.UniqueObjectOther
           |class A {
           |  UniqueObjectOther
           |  def completeThisUniqueName() = 42
           |}
           |""".stripMargin,
      )
      // check if the change name is piecked up despite the file not compiling
      newText = """|package a
                   |
                   |class A {
                   |  <<UniqueObjectOther>>
                   |  def completeThisUniqueName() = 42
                   |}
                   |""".stripMargin
      input = newText.replace("<<", "").replace(">>", "")
      _ <- server.didSave("a/src/main/scala/a/A.scala") { _ =>
        newText.replace("<<", "").replace(">>", "")
      }
      codeActions <-
        server
          .assertCodeAction(
            "a/src/main/scala/a/A.scala",
            newText,
            s"""|${ImportMissingSymbol.title("UniqueObjectOther", "b")}
                |${CreateNewSymbol.title("UniqueObjectOther")}
                |""".stripMargin,
            kind = Nil,
          )
      // make sure that the now change UniqueObject is not suggested
      _ <- server.didSave("a/src/main/scala/a/A.scala") { _ =>
        input.replace("UniqueObjectOther", "UniqueObject")
      }
      codeActions <-
        server
          .assertCodeAction(
            "a/src/main/scala/a/A.scala",
            newText.replace("UniqueObjectOther", "UniqueObject"),
            s"""|${CreateNewSymbol.title("UniqueObject")}
                |""".stripMargin,
            kind = Nil,
          )
    } yield ()
  }

  test("never-compiling") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """/metals.json
          |{
          |  "a": {}
          |}
          |/a/src/main/scala/a/A.scala
          |package a
          |class A {
          |  // @@
          |  def completeThisUniqueName(): String = 42
          |}
          |/a/src/main/scala/b/B.scala
          |package b
          |object UniqueObject {
          |  def completeThisUniqueName() = 42
          |  def completeThisUniqueName2(): String = 42
          |}
          |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ <- server.didOpen("a/src/main/scala/b/B.scala")
      _ = assertNoDiff(
        server.client.workspaceDiagnostics,
        """|a/src/main/scala/a/A.scala:4:42: error: type mismatch;
           | found   : Int(42)
           | required: String
           |  def completeThisUniqueName(): String = 42
           |                                         ^^
           |a/src/main/scala/b/B.scala:4:43: error: type mismatch;
           | found   : Int(42)
           | required: String
           |  def completeThisUniqueName2(): String = 42
           |                                          ^^
           |""".stripMargin,
      )
      _ <- assertCompletion(
        "b.UniqueObject.completeThisUniqueNa@@",
        """|completeThisUniqueName(): Int
           |completeThisUniqueName2(): String""".stripMargin,
      )
      _ <- assertCompletionEdit(
        "UniqueObject@@",
        """|package a
           |
           |import b.UniqueObject
           |class A {
           |  UniqueObject
           |  def completeThisUniqueName(): String = 42
           |}
           |""".stripMargin,
      )
    } yield ()
  }

  test("never-compiling2") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """/metals.json
          |{
          |  "a": {}
          |}
          |/a/src/main/scala/a/A.scala
          |package a
          |class A {
          |  // @@
          |  def completeThisUniqueName(): String = 42
          |}
          |/a/src/main/scala/b/B.scala
          |package b
          |trait BTrait {
          |  def completeThisUniqueName3() = 42
          |}
          |class B
          |/a/src/main/scala/c/C.scala
          |package c
          |import b.BTrait
          |import b.B
          |
          |object UniqueObject extends BTrait {
          |  def completeThisUniqueName() = 42
          |  def completeThisUniqueName2(b: B): String = 42
          |}
          |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/c/C.scala")
      _ = assertNoDiff(
        server.client.workspaceDiagnostics,
        """|a/src/main/scala/a/A.scala:4:42: error: type mismatch;
           | found   : Int(42)
           | required: String
           |  def completeThisUniqueName(): String = 42
           |                                         ^^
           |a/src/main/scala/c/C.scala:7:47: error: type mismatch;
           | found   : Int(42)
           | required: String
           |  def completeThisUniqueName2(b: B): String = 42
           |                                              ^^
           |""".stripMargin,
      )
      _ <- assertCompletion(
        "c.UniqueObject.completeThisUniqueNa@@",
        """|completeThisUniqueName(): Int
           |completeThisUniqueName2(b: B): String
           |completeThisUniqueName3(): Int""".stripMargin,
      )
    } yield ()
  }

  test("never-compiling-reverse-order") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """/metals.json
          |{
          |  "a": {}
          |}
          |/a/src/main/scala/a/A.scala
          |package a
          |case class A(bar: String)
          |object O {
          |  type T = A
          |}
          |/a/src/main/scala/b/B.scala
          |package b
          |import a.O.T
          |
          |object B {
          |  def getT: T = ???
          |}
          |/a/src/main/scala/c/C.scala
          |package c
          |import b.B
          |
          |object C {
          |  val i: Int = "aaa"
          |  def foo = B.getT
          |  def bar = foo.bar
          |}
          |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/c/C.scala")
      _ = assertNoDiff(
        server.client.workspaceDiagnostics,
        """|a/src/main/scala/c/C.scala:5:16: error: type mismatch;
           | found   : String("aaa")
           | required: Int
           |  val i: Int = "aaa"
           |               ^^^^^
           |""".stripMargin,
      )
      _ <- assertCompletion(
        "  def bar = foo.bar@@",
        "bar: String",
        filename = Some("a/src/main/scala/c/C.scala"),
      )
    } yield ()
  }
}
