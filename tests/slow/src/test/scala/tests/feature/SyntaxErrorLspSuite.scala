package tests.feature

import scala.concurrent.Future

import scala.meta.internal.metals.{BuildInfo => V}

import munit.Location
import tests.BaseLspSuite

class SyntaxErrorLspSuite extends BaseLspSuite("syntax-error") {

  case class Assert(didChange: String => String, expectedDiagnostics: String)
  def check(
      name: String,
      code: String,
      asserts: Assert*
  )(implicit loc: Location): Unit = {
    test(name) {
      def runAsserts(as: List[Assert]): Future[Unit] =
        as match {
          case Nil => Future.successful(())
          case assert :: tail =>
            server
              .didChange("a/src/main/scala/A.scala")(assert.didChange)
              .flatMap { _ =>
                assertNoDiff(
                  client.workspaceDiagnostics,
                  assert.expectedDiagnostics
                )
                runAsserts(tail)
              }
        }

      for {
        _ <- server.initialize(
          s"""
             |/metals.json
             |{"a": {}}
             |/a/src/main/scala/A.scala
             |""".stripMargin + code
        )
        _ <- server.didOpen("a/src/main/scala/A.scala")
        _ = assertNotEmpty(client.workspaceDiagnostics)
        _ <- runAsserts(asserts.toList)
      } yield ()
    }
  }

  test("basic") {
    for {
      _ <- server.initialize(
        """|
           |/metals.json
           |{"a": {}}
           |/project/plugins.sbt
           |lazy lazy val x = 1
           |/Main.scala
           |object object A
           |""".stripMargin
      )
      _ <- server.didOpen("Main.scala")
      _ <- server.didOpen("project/plugins.sbt")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """
          |Main.scala:1:8: error: identifier expected but object found
          |object object A
          |       ^^^^^^
          |project/plugins.sbt:1:6: error: repeated modifier
          |lazy lazy val x = 1
          |     ^^^^
          |""".stripMargin
      )
      _ <- server.didClose("Main.scala")
      _ <- server.didClose("project/plugins.sbt")
      _ = assertNoDiff(client.workspaceDiagnostics, "")
      _ <- server.didOpen("Main.scala")
      _ <- server.didOpen("project/plugins.sbt")
      _ <- server.didSave("Main.scala")(_ => "object A\n")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """
          |project/plugins.sbt:1:6: error: repeated modifier
          |lazy lazy val x = 1
          |     ^^^^
          |""".stripMargin
      )
      _ <- server.didSave("project/plugins.sbt")(_ => "lazy val x = 1\n")
      _ = assertNoDiff(client.workspaceDiagnostics, "")
    } yield ()
  }

  test("mix1") {
    for {
      _ <- server.initialize(
        """|
           |/metals.json
           |{"a": {}}
           |/a/src/main/scala/Main.scala
           |object object A
           |object object B
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/Main.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/scala/Main.scala:1:8: error: identifier expected but 'object' found.
           |object object A
           |       ^^^^^^
           |a/src/main/scala/Main.scala:2:8: error: identifier expected but 'object' found.
           |object object B
           |       ^^^^^^
           |""".stripMargin
      )
      _ <- server.didChange("a/src/main/scala/Main.scala")(t => "\n" + t)
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/scala/Main.scala:2:8: error: identifier expected but 'object' found.
           |object object A
           |       ^^^^^^
           |a/src/main/scala/Main.scala:3:8: error: identifier expected but 'object' found.
           |object object B
           |       ^^^^^^
           |""".stripMargin
      )
    } yield ()
  }

  test("mix2") {
    for {
      _ <- server.initialize(
        """|
           |/metals.json
           |{"a": {}}
           |/a/src/main/scala/Main.scala
           |object Main {
           |  val b: Int = ""
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/Main.scala")
      _ <- server.didChange("a/src/main/scala/Main.scala")(
        _.replace("val b", "val\n  val b")
      )
      _ <- server.didChange("a/src/main/scala/Main.scala")(
        _.replace("val\n", "val \n")
      )
      _ <- server.didChange("a/src/main/scala/Main.scala")(
        _.replace("val \n", "")
      )
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/scala/Main.scala:2:18: error: type mismatch;
           | found   : String("")
           | required: Int
           |    val b: Int = ""
           |                 ^^
           |""".stripMargin
      )
    } yield ()
  }

  test("no-build-tool") {
    for {
      _ <- server.initialize(
        """
          |/A.scala
          |object A { val x = }
          |""".stripMargin,
        expectError = true
      )
      _ <- server.didOpen("A.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|A.scala:1:20: error: illegal start of simple expression
           |object A { val x = }
           |                   ^
           |""".stripMargin
      )
    } yield ()
  }

  test("unclosed-literal") {
    for {
      _ <- server.initialize(
        """
          |/metals.json
          |{"a": {}}
          |/a/src/main/scala/A.scala
          |object A {
          |  val x: Int = ""
          |}
          |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/A.scala")
      typeMismatch = {
        """|a/src/main/scala/A.scala:2:16: error: type mismatch;
           | found   : String("")
           | required: Int
           |  val x: Int = ""
           |               ^^
           |""".stripMargin
      }
      _ = assertNoDiff(client.workspaceDiagnostics, typeMismatch)
      _ <- server.didChange("a/src/main/scala/A.scala")(
        _.replace("\"\"", "\"")
      )
      // assert that a tokenization error results in a single diagnostic, hides type errors.
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/scala/A.scala:2:16: error: unclosed string literal
           |  val x: Int = "
           |               ^
           |""".stripMargin
      )
      _ <- server.didChange("a/src/main/scala/A.scala")(
        _.replace("\"", "\"\"\n  // close")
      )
      // assert that once the tokenization error is fixed, the type error reappears.
      _ = assertNoDiff(client.workspaceDiagnostics, typeMismatch)
    } yield ()
  }

  check(
    "sticky",
    """|object A {
       |  "".lengthCompare("1".substring(0))
       |}
       |""".stripMargin,
    Assert(
      _.replace("\"1\".substring(0)", ""),
      """|a/src/main/scala/A.scala:2:19: error: type mismatch;
         | found   : String
         | required: Int
         |  "".lengthCompare()
         |                  ^^
         |""".stripMargin
    ),
    Assert(
      _.replace(".lengthCompare()", "."),
      """|a/src/main/scala/A.scala:2:5: error: type mismatch;
         | found   : String
         | required: Int
         |  "".
         |    ^
         |a/src/main/scala/A.scala:3:1: error: identifier expected but } found
         |}
         |^
         |""".stripMargin
    )
  )

  check(
    "stable1",
    """|object A {
       |  "".lengthCompare()
       |}
       |""".stripMargin,
    Assert(
      _.replace("object A", "object B"),
      """|a/src/main/scala/A.scala:2:3: error: not enough arguments for method lengthCompare: (len: Int)Int.
         |Unspecified value parameter len.
         |  "".lengthCompare()
         |  ^^^^^^^^^^^^^^^^^^
         |""".stripMargin
    )
  )

  check(
    "stable2",
    """|object A {
       |  val x: Int = ""
       |}
       |""".stripMargin,
    Assert(
      _.replace("\"\"", "\"a\" // comment"),
      """|a/src/main/scala/A.scala:2:29: error: type mismatch;
         | found   : String("")
         | required: Int
         |  val x: Int = "a" // comment
         |                            ^
         |""".stripMargin
    )
  )

  check(
    "stable3",
    """|object A {
       |  val x: Int = "a" + "b"
       |}
       |""".stripMargin,
    Assert(
      _.replace("\"b\"", "\"c\""),
      """|a/src/main/scala/A.scala:2:16: error: type mismatch;
         | found   : String("ab")
         | required: Int
         |  val x: Int = "a" + "c"
         |               ^^^^^^^^^
         |""".stripMargin
    )
  )

  check(
    "strip",
    """|object A {
       |  val x: Int = "a" + "b"
       |}
       |""".stripMargin,
    Assert(
      _.replace("t = \"a\" + \"b\"", ""),
      """|a/src/main/scala/A.scala:2:11: error: type mismatch;
         | found   : String("ab")
         | required: Int
         |  val x: In
         |          ^
         |""".stripMargin
    )
  )

  test("literal-types") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        s"""
           |/metals.json
           |{"a": { "scalaVersion": "${V.scala213}" }}
           |/a/src/main/scala/A.scala
           |object A {
           |  val x: Option["literal"] = Some("literal")
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/A.scala")
      _ = assertEmpty(client.workspaceDiagnostics)
    } yield ()
  }

}
