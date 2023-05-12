package tests.`best-effort`

import tests.BaseCompletionLspSuite
import munit.Assertions._

class BestEffortCompilationSuite
    extends BaseCompletionLspSuite("best-effort-compilation") { // TODO names

  // implemented in bloop
  test("best-effort-error-diagnostics") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{
           |  "a": {"scalaVersion": "3.3.1-RC1-bin-SNAPSHOT" },
           |  "b": {
           |    "scalaVersion": "3.3.1-RC1-bin-SNAPSHOT",
           |    "dependsOn": [ "a" ]
           |  }
           |}
           |/a/src/main/scala/sample/A.scala
           |package sample
           |
           |object A:
           |  def foo: Int = "error"
           |  def bar: String = "correct"
           |/b/src/main/scala/sample/B.scala
           |package sample
           |
           |object B:
           |  A.bar
           |  A.foo
           |  A.unknown
           |""".stripMargin
      )
      _ <- server.didOpen("b/src/main/scala/sample/B.scala")
      // Here we check that there is an error in `a`
      // but due to the best effort mode `b` was compiled too and `A.foo` and `A.bar` are valid members
      // The only error in `b` is a reference to not defined member
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/scala/sample/A.scala:4:18: error: Found:    ("error" : String)
           |Required: Int
           |  def foo: Int = "error"
           |                 ^^^^^^^
           |b/src/main/scala/sample/B.scala:6:3: error: value unknown is not a member of object sample.A
           |  A.unknown
           |  ^^^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  // implemented in metals
  test("best-effort-completion-one-module") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "a": { "scalaVersion": "3.3.1-RC1-bin-SNAPSHOT" }
           |}
           |/a/src/main/scala/a/A.scala
           |package a
           |
           |object A {
           |  // @@
           |}
           |/a/src/main/scala/a/DefinedInA.scala
           |package a
           |
           |object DefinedInA {
           |  def bFoo: Int = "asdsdd"
           |  def bBar: String = "asdsad"
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/DefinedInA.scala")
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/scala/a/DefinedInA.scala:4:19: error: Found:    ("asdsdd" : String)
           |Required: Int
           |  def bFoo: Int = "asdsdd"
           |                  ^^^^^^^^
           |""".stripMargin,
      )
      _ <- assertCompletion(
        "DefinedInA.b@@",
        """|bBar: String
           |bFoo: Int
           |""".stripMargin,
        includeDetail = false,
      )
    } yield ()
  }

  test("best-effort-completion-multiple-modules") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "b": { "scalaVersion": "3.3.1-RC1-bin-SNAPSHOT" },
           |  "a": {
           |    "scalaVersion": "3.3.1-RC1-bin-SNAPSHOT",
           |    "dependsOn": [ "a" ]
           |  }
           |}
           |/b/src/main/scala/b/B.scala
           |object B {
           |  def bFoo: Int = "asdsdd"
           |  def bBar: String = "asdsad"
           |}
           |
           |/a/src/main/scala/a/A.scala
           |object A {
           |  // @@
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("b/src/main/scala/b/B.scala")
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      // _ = assertNoDiff(
      //   client.workspaceDiagnostics,
      //   """a/src/main/scala/a/A.scala:4:19: error: Found:    ("asdsdd" : String)
      //      |Required: Int
      //      |  def bFoo: Int = "asdsdd"
      //      |                  ^^^^^^^^
      //      |""".stripMargin
      // )
      _ <- assertCompletion(
        "B.b@@",
        """|bBar: String
           |bFoo: Int
           |""".stripMargin,
        includeDetail = false,
      )
    } yield ()
  }

  // Here we test wheater completion symbols persist after removing
  // the object they originate from
  test("best-effort-completion-multiple-modules-with-removed-objects") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "b": { "scalaVersion": "3.3.1-RC1-bin-SNAPSHOT" },
           |  "a": {
           |    "scalaVersion": "3.3.1-RC1-bin-SNAPSHOT",
           |    "dependsOn": [ "b" ]
           |  }
           |}
           |/b/src/main/scala/b/B.scala
           |object BCustomCorrectObject
           |object BCustomForcedError {
           |  def a: Int = "string" // error
           |}
           |
           |/a/src/main/scala/a/A.scala
           |object A {
           |  // @@
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("b/src/main/scala/b/B.scala")
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ <- assertCompletion(
        "BCustom@@",
        """|BCustomCorrectObject <empty>
           |BCustomForcedError <empty>
           |""".stripMargin,
        includeDetail = false,
      )
      // We replace the BCustomCorrectObject object with BCustomChangedObject,
      // and check if the completions were correctly updated
      _ <- server.didChange("b/src/main/scala/b/B.scala") { _ =>
        s"""|object BCustomChangedObject
            |object BCustomForcedError {
            |  def a: Int = "string" // error
            |}
            |""".stripMargin
      }
      _ <- server.didSave("b/src/main/scala/b/B.scala")(a => a)
      _ <- assertCompletion(
        "BCustom@@",
        """|BCustomChangedObject <empty>
           |BCustomForcedError <empty>
        """.stripMargin,
      )
    } yield ()
  }

  // we check if previous sucessful best effort compilation artefacts remain
  // after an unsuccessful best effort attempt (TODO - needs some work)
  // test("best-effort-completion-after-crash") {
  //   cleanWorkspace()
  //   for {
  //     // we start with forcing best effort artifacts to appear
  //     _ <- initialize{
  //       s"""/metals.json
  //          |{
  //          |  "a": { "scalaVersion": "3.3.1-RC1-bin-SNAPSHOT" },
  //          |  "b": {
  //          |    "scalaVersion": "3.3.1-RC1-bin-SNAPSHOT",
  //          |    "dependsOn": [ "a" ]
  //          |  }
  //          |}
  //          |/a/src/main/scala/sample/A.scala
  //          |package sample
  //          |
  //          |object A:
  //          |  def foo: Int = "error"
  //          |  def bar: String = "correct"
  //          |/b/src/main/scala/sample/B.scala
  //          |package sample
  //          |
  //          |object B:
  //          |  A.bar
  //          |  A.foo
  //          |  A.unknown
  //          |""".stripMargin
  //     }
  //     _ <- server.didOpen("b/src/main/scala/sample/B.scala")
  //     _ = assertNoDiff(
  //       client.workspaceDiagnostics,
  //       """|a/src/main/scala/sample/A.scala:4:18: error: Found:    ("error" : String)
  //          |Required: Int
  //          |  def foo: Int = "error"
  //          |                 ^^^^^^^
  //          |b/src/main/scala/sample/B.scala:6:3: error: value unknown is not a member of object sample.A
  //          |  A.unknown
  //          |  ^^^^^^^^^
  //          |""".stripMargin,
  //     )
  //     // then we change the class to contain an error that will
  //     // not be handled by the best-effort compilation and check that
  //     // there is still an attempted compilation in the "b" project,
  //     // based on previous successful best effort
  //     _ <- server.didChange("a/src/main/scala/sample/A.scala") { _ =>
  //       s"""|package sample
  //           |
  //           |object A:
  //           |  def foo: Int = "error"
  //           |  def bar: String = "correct"
  //           |  def addedSymbol: String = "added" // should not show
  //           |  type X = (U, U) // error: cycle
  //           |  type U = X & Int
  //           |""".stripMargin
  //     }
  //     _ <- server.didSave("a/src/main/scala/sample/A.scala")(a => a)
  //     _ <- server.didChange("b/src/main/scala/sample/B.scala") { _ =>
  //       """|package sample
  //          |
  //          |object B:
  //          |  A.bar
  //          |  A.foo
  //          |  A.unknown
  //          |  A.addedSymbol // TODO should be errored out
  //          |""".stripMargin
  //     }
  //     _ <- server.didSave("b/src/main/scala/sample/B.scala")(a => a)
  //     // _ <- server.didOpen("b/src/main/scala/sample/B.scala")
  //     _ = assertNoDiff(
  //       client.workspaceDiagnostics,
  //       """|a/src/main/scala/sample/A.scala:4:18: error: Found:    ("error" : String)
  //          |Required: Int
  //          |  def foo: Int = "error"
  //          |                 ^^^^^^^
  //          |a/src/main/scala/sample/A.scala:7:8: error: illegal cyclic type reference: alias (sample.A.U, sample.A.U) of type X refers back to the type itself
  //          |  type X = (U, U) // error: cycle
  //          |       ^
  //          |b/src/main/scala/sample/B.scala:6:3: error: value unknown is not a member of object sample.A
  //          |  A.unknown
  //          |  ^^^^^^^^^
  //          |""".stripMargin,
  //     )
  //   } yield ()
  // }
}
