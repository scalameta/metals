package tests

import java.time.Duration

import scala.meta.internal.metals.EmptyCancelToken

class PCDiagnosticsSuite
    extends BaseLspSuite("pc-diagnostics")
    with BaseSourcePathSuite {

  for (ownerFirst <- List(false, true))
    test(s"semanticdb-binary-source-owner-first-$ownerFirst") {
      cleanWorkspace()
      val owner = "a/src/main/scala/sample/Envelope.scala"
      val dependency = "a/src/main/scala/sample/Payload.scala"
      val first = if (ownerFirst) owner else dependency
      val second = if (ownerFirst) dependency else owner
      for {
        _ <- initialize(
          """|/metals.json
             |{"a": {}}
             |/a/src/main/scala/sample/Envelope.scala
             |package sample
             |trait Envelope {
             |  def payload: Payload
             |  def discard(): Envelope.Result = payload.discard()
             |}
             |object Envelope {
             |  final class Result
             |  val completed: Result = new Result
             |}
             |/a/src/main/scala/sample/Payload.scala
             |package sample
             |class Payload {
             |  def discard(): Envelope.Result = Envelope.completed
             |}
             |""".stripMargin
        )
        // Populate the class directory before typing either source with the PC.
        _ <- server.server.compilations.compileFile(server.toPath(dependency))
        _ = assertNoDiagnostics()
        firstDocs <- server.server.compilers.batchSemanticdbTextDocuments(
          List(server.toPath(first)),
          EmptyCancelToken,
          Duration.ofSeconds(15),
          useFallbackCompiler = false,
        )
        _ = assertEquals(firstDocs.documents.size, 1)
        _ = assertEquals(
          firstDocs.documents.head.uri,
          server.toPath(first).toURI.toString,
        )
        _ = assert(firstDocs.documents.head.occurrences.nonEmpty)
        _ = assertEquals(firstDocs.documents.flatMap(_.diagnostics), Nil)
        secondDocs <- server.server.compilers.batchSemanticdbTextDocuments(
          List(server.toPath(second)),
          EmptyCancelToken,
          Duration.ofSeconds(15),
          useFallbackCompiler = false,
        )
        _ = assertEquals(secondDocs.documents.size, 1)
        _ = assertEquals(
          secondDocs.documents.head.uri,
          server.toPath(second).toURI.toString,
        )
        _ = assert(secondDocs.documents.head.occurrences.nonEmpty)
        _ = assertEquals(secondDocs.documents.flatMap(_.diagnostics), Nil)
        repeated <- server.server.compilers.batchSemanticdbTextDocuments(
          List(server.toPath(second)),
          EmptyCancelToken,
          Duration.ofSeconds(15),
          useFallbackCompiler = false,
        )
        _ = assertEquals(repeated, secondDocs)
      } yield ()
    }

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
      docs <- server.server.compilers.batchSemanticdbTextDocuments(
        List(server.toPath("a/src/main/scala/a/TypeErrors.scala")),
        EmptyCancelToken,
        Duration.ofSeconds(15),
        useFallbackCompiler = false,
      )
      _ = assertEquals(docs.documents.size, 1)
      _ = assertEquals(docs.documents.head.diagnostics.size, 2)
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
