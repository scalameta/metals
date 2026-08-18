package tests

class PCDiagnosticsSuite
    extends BaseLspSuite("pc-diagnostics")
    with BaseSourcePathSuite {

  test("no-errors") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|
           |/metals.json
           |{
           |  "a": {}
           |}
           |/a/src/main/scala/a/Example.scala
           |package a
           |
           |object Example {
           |  val message = "Hello, World!"
           |  def greet(name: String): String = s"Hello, $name!"
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Example.scala")
      _ = assertNoDiagnostics()
    } yield ()
  }

  test("simple-type-error") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|
           |/metals.json
           |{
           |  "a": {}
           |}
           |/a/src/main/scala/a/TypeErrors.scala
           |package a
           |
           |object TypeErrors {
           |  val number: Int = "not a number"
           |  val text: String = 42
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/TypeErrors.scala")
      _ <- server.didFocus("a/src/main/scala/a/TypeErrors.scala")
      // diagnostics are sent asynchronously and we don't have a future to await here
      // so we wait until the diagnostics are available or 5 seconds have passed
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/scala/a/TypeErrors.scala:4:21: error: type mismatch;
           | found   : String("not a number")
           | required: Int
           |  val number: Int = "not a number"
           |                    ^^^^^^^^^^^^^^
           |a/src/main/scala/a/TypeErrors.scala:5:22: error: type mismatch;
           | found   : Int(42)
           | required: String
           |  val text: String = 42
           |                     ^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("introduce-type-error") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|
           |/metals.json
           |{
           |  "a": {}
           |}
           |/a/src/main/scala/a/Dynamic.scala
           |package a
           |
           |object Dynamic {
           |  val number: Int = 42
           |  val text: String = "hello"
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Dynamic.scala")
      _ <- server.didFocus("a/src/main/scala/a/Dynamic.scala")
      _ = assertNoDiagnostics()
      // Introduce a type error by changing the string assignment to an int
      _ <- server.didChange("a/src/main/scala/a/Dynamic.scala")(
        _.replace("val text: String = \"hello\"", "val text: String = 123")
      )
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/scala/a/Dynamic.scala:5:22: error: type mismatch;
           | found   : Int(123)
           | required: String
           |  val text: String = 123
           |                     ^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("prefer-non-shim-definition-for-diagnostics") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|
           |/metals.json
           |{
           |  "a": {}
           |}
           |/a/src/main/scala/a/shims.scala
           |package a
           |
           |class Duplicate {
           |  def shimOnly: String = "shim"
           |}
           |/a/src/main/scala/a/Duplicate.scala
           |package a
           |
           |class Duplicate {
           |  def realOnly: String = "real"
           |}
           |/a/src/main/scala/a/Main.scala
           |package a
           |
           |object Main {
           |  val value = new Duplicate().shimOnly
           |}
           |""".stripMargin
      )
      _ <- server.didChangeConfiguration(
        """{
          |  "build-on-focus": false,
          |  "build-on-change": false,
          |  "shimGlobs": {
          |    "default": ["shims.scala"]
          |  }
          |}
          |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      _ <- server.didFocus("a/src/main/scala/a/Main.scala")
      _ = assertContains(
        client.workspaceDiagnostics,
        "value shimOnly is not a member of a.Duplicate",
      )
    } yield ()
  }

  test("keep-build-tool-warnings") {
    cleanWorkspace()
    val file = "a/src/main/scala/a/Mixed.scala"
    for {
      _ <- initialize(
        """|
           |/metals.json
           |{
           |  "a": {
           |     "scalacOptions": [
           |       "-Ywarn-unused"
           |     ]
           |   }
           |}
           |/a/src/main/scala/a/Mixed.scala
           |package a
           |import scala.util.Failure // unused
           |object Mixed {
           |  val text: String = "hello"
           |}
           |""".stripMargin
      )
      _ <- server.didOpen(file)
      _ <- server.server.compilations.compileFile(server.toPath(file))
      _ <- server.didFocus(file)
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/scala/a/Mixed.scala:2:19: warning: Unused import
           |import scala.util.Failure // unused
           |                  ^^^^^^^
           |""".stripMargin,
      )
      _ <- server.didChange(file)(
        _.replace("val text: String = \"hello\"", "val text: String = 123")
      )
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/scala/a/Mixed.scala:2:19: warning: Unused import
           |import scala.util.Failure // unused
           |                  ^^^^^^^
           |a/src/main/scala/a/Mixed.scala:4:22: error: type mismatch;
           | found   : Int(123)
           | required: String
           |  val text: String = 123
           |                     ^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("dedup-overlapping-type-error") {
    cleanWorkspace()
    val file = "a/src/main/scala/a/DuplicateError.scala"
    for {
      _ <- initialize(
        """|
           |/metals.json
           |{
           |  "a": {}
           |}
           |/a/src/main/scala/a/DuplicateError.scala
           |package a
           |object DuplicateError {
           |  val text: String = 42
           |}
           |""".stripMargin
      )
      _ <- server.didOpen(file)
      _ <- server.server.compilations.compileFile(server.toPath(file))
      _ <- server.didFocus(file)
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/scala/a/DuplicateError.scala:3:22: error: type mismatch;
           | found   : Int(42)
           | required: String
           |  val text: String = 42
           |                     ^^
           |""".stripMargin,
      )
    } yield ()
  }

}
