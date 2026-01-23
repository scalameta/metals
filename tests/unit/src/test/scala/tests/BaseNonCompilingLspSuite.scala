package tests

import scala.concurrent.Future

import scala.meta.internal.metals.codeactions.CreateNewSymbol
import scala.meta.internal.metals.codeactions.ExplainDiagnostic
import scala.meta.internal.metals.codeactions.ImportMissingSymbol
import scala.meta.internal.metals.codeactions.SourceAddMissingImports

abstract class BaseNonCompilingLspSuite(name: String)
    extends BaseCompletionLspSuite(name) {

  def scalaVersion: String
  def scalaVersionConfig: String = s"\"scalaVersion\": \"${scalaVersion}\""

  val saveAfterChanges: Boolean
  val scala3Diagnostics: Boolean

  test("non-compiling") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "a": {$scalaVersionConfig}
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
      _ <-
        if (saveAfterChanges)
          server.didSave("a/src/main/scala/b/B.scala")
        else Future.unit
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
      _ <-
        if (saveAfterChanges)
          server.didSave("a/src/main/scala/b/B.scala")
        else Future.unit
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
      // check if the change name is picked up despite the file not compiling
      newText = """|package a
                   |
                   |class A {
                   |  <<UniqueObjectOther>>
                   |  def completeThisUniqueName() = 42
                   |}
                   |""".stripMargin
      input = newText.replace("<<", "").replace(">>", "")
      _ <- server.didChange("a/src/main/scala/a/A.scala") { _ =>
        newText.replace("<<", "").replace(">>", "")
      }
      _ <- server.didSave("a/src/main/scala/a/A.scala")
      _ <-
        server
          .assertCodeAction(
            "a/src/main/scala/a/A.scala",
            newText,
            if (scalaVersion.startsWith("3"))
              s"""|${ImportMissingSymbol.title("UniqueObjectOther", "b")}
                  |${SourceAddMissingImports.title}
                  |${CreateNewSymbol.title("UniqueObjectOther")}
                  |${ExplainDiagnostic.title}
                  |""".stripMargin
            else
              s"""|${ImportMissingSymbol.title("UniqueObjectOther", "b")}
                  |${SourceAddMissingImports.title}
                  |${CreateNewSymbol.title("UniqueObjectOther")}
                  |""".stripMargin,
            kind = Nil,
          )
      // make sure that the now change UniqueObject is not suggested
      _ <- server.didChange("a/src/main/scala/a/A.scala") { _ =>
        input.replace("UniqueObjectOther", "UniqueObject")
      }
      _ <- server.didSave("a/src/main/scala/a/A.scala")
      _ <-
        server
          .assertCodeAction(
            "a/src/main/scala/a/A.scala",
            newText.replace("UniqueObjectOther", "UniqueObject"),
            if (scalaVersion.startsWith("3"))
              s"""|${CreateNewSymbol.title("UniqueObject")}
                  |${ExplainDiagnostic.title}
                  |""".stripMargin
            else
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
        s"""/metals.json
           |{
           |  "a": {$scalaVersionConfig}
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
        if (scala3Diagnostics)
          """|a/src/main/scala/a/A.scala:4:42: error: Found:    (42 : Int)
             |Required: String
             |  def completeThisUniqueName(): String = 42
             |                                         ^^
             |a/src/main/scala/b/B.scala:4:43: error: Found:    (42 : Int)
             |Required: String
             |  def completeThisUniqueName2(): String = 42
             |                                          ^^
             |""".stripMargin
        else
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
        s"""/metals.json
           |{
           |  "a": {$scalaVersionConfig}
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
        if (scala3Diagnostics)
          """|a/src/main/scala/a/A.scala:4:42: error: Found:    (42 : Int)
             |Required: String
             |  def completeThisUniqueName(): String = 42
             |                                         ^^
             |a/src/main/scala/c/C.scala:7:47: error: Found:    (42 : Int)
             |Required: String
             |  def completeThisUniqueName2(b: B): String = 42
             |                                              ^^
             |""".stripMargin
        else
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
        s"""/metals.json
           |{
           |  "a": {$scalaVersionConfig}
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
        if (scala3Diagnostics)
          """|a/src/main/scala/c/C.scala:5:16: error: Found:    ("aaa" : String)
             |Required: Int
             |  val i: Int = "aaa"
             |               ^^^^^
             |""".stripMargin
        else
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
      _ <- server.didSave("a/src/main/scala/b/B.scala")
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
      _ <- server.didSave("a/src/main/scala/a/A.scala")
      _ <-
        server
          .assertCodeAction(
            "a/src/main/scala/a/A.scala",
            newText,
            s"""|${ImportMissingSymbol.title("UniqueObject", "b.W")}
                |${SourceAddMissingImports.title}
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
