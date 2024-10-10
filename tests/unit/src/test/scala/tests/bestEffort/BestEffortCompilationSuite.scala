package tests.bestEffort

import scala.meta.internal.metals.MetalsServerConfig

import tests.BaseNonCompilingLspSuite

class BestEffortCompilationSuite
    extends BaseNonCompilingLspSuite("best-effort-compilation") {
  val scalaVersion = "3.5.0-RC7"

  override def serverConfig: MetalsServerConfig =
    super.serverConfig.copy(enableBestEffort = true)

  override val scalaVersionConfig: String =
    s"\"scalaVersion\": \"${scalaVersion}\""
  override val saveAfterChanges: Boolean = true
  override val scala3Diagnostics: Boolean = true

  // implemented in bloop
  test("best-effort-error-diagnostics") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/metals.json
            |{
            |  "a": { $scalaVersionConfig },
            |  "b": {
            |    $scalaVersionConfig,
            |    "dependsOn": [ "a" ]
            |  }
            |}
            |/a/src/main/scala/sample/A.scala
            |package sample
            |object A:
            |  def foo: Int = "error"
            |  def bar: String = "correct"
            |/b/src/main/scala/sample/B.scala
            |package sample
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
        """|a/src/main/scala/sample/A.scala:3:18: error: Found:    ("error" : String)
           |Required: Int
           |  def foo: Int = "error"
           |                 ^^^^^^^
           |b/src/main/scala/sample/B.scala:5:3: error: value unknown is not a member of object sample.A
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
           |  "a": { $scalaVersionConfig }
           |}
           |/a/src/main/scala/a/A.scala
           |package a
           |object A {
           |  // @@
           |}
           |/a/src/main/scala/a/DefinedInA.scala
           |package a
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
        """|a/src/main/scala/a/DefinedInA.scala:3:19: error: Found:    ("asdsdd" : String)
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
           |  "a": {
           |    $scalaVersionConfig,
           |    "dependsOn": [ "b" ]
           |  },
           |  "b": { $scalaVersionConfig }
           |}
           |/a/src/main/scala/a/A.scala
           |package a
           |object A {
           |  // @@
           |}
           |/b/src/main/scala/b/B.scala
           |package b
           |object B {
           |  def bFoo: Int = "asdsdd"
           |  def bBar: String = "asdsad"
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("b/src/main/scala/b/B.scala")
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """b/src/main/scala/b/B.scala:3:19: error: Found:    ("asdsdd" : String)
          |Required: Int
          |  def bFoo: Int = "asdsdd"
          |                  ^^^^^^^^
          |""".stripMargin,
      )
      _ <- assertCompletion(
        "b.B.b@@",
        """|bBar: String
           |bFoo: Int
           |""".stripMargin,
        includeDetail = false,
      )
    } yield ()
  }

  // Here we test whether completion symbols persist after removing
  // the object they originate from
  test("best-effort-completion-multiple-modules-with-removed-objects") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "b": { $scalaVersionConfig },
           |  "a": {
           |    $scalaVersionConfig,
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
      _ <- server.didSave("b/src/main/scala/b/B.scala")(identity)
      _ <- assertCompletion(
        "BCustom@@",
        """|BCustomChangedObject <empty>
           |BCustomForcedError <empty>
        """.stripMargin,
      )
    } yield ()
  }

  // we check if previous successful best effort compilation artifacts remain
  // after an unsuccessful best effort attempt.
  // Unsuccessful best effort attempts tend surface detailed exceptions
  // from bloop, so those will show up while this test is running.
  test("best-effort-completion-after-crash") {
    cleanWorkspace()
    for {
      // we start with forcing best effort artifacts to appear
      _ <- initialize {
        s"""/metals.json
           |{
           |  "b": { $scalaVersionConfig },
           |  "a": {
           |    $scalaVersionConfig,
           |    "dependsOn": [ "b" ]
           |  }
           |}
           |/b/src/main/scala/b/B.scala
           |
           |object B:
           |  def completionFoo: Int = "error"
           |  def completionBar: String = "correct"
           |/a/src/main/scala/a/A.scala
           |
           |object A:
           |  B.completionBar
           |  B.completionFoo
           |  B.completionUnknown
           |""".stripMargin
      }
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/scala/a/A.scala:5:3: error: value completionUnknown is not a member of object B
           |  B.completionUnknown
           |  ^^^^^^^^^^^^^^^^^^^
           |b/src/main/scala/b/B.scala:3:28: error: Found:    ("error" : String)
           |Required: Int
           |  def completionFoo: Int = "error"
           |                           ^^^^^^^
           |""".stripMargin,
      )
      // then we change the class to contain an error that will
      // not be handled by the best-effort compilation and check that
      // the previously created betasty are not overwritten
      _ <- server.didChange("b/src/main/scala/b/B.scala") { _ =>
        s"""|object B:
            |  def completionFoo: Int = "error"
            |  def completionBar: String = "correct"
            |  def completionAdded: String = "added" // should not show
            |
            |  trait QC:
            |    object tasty:
            |      type Tree
            |      extension (tree: Tree)
            |        def pos: Tree = ???
            |
            |  def test =
            |    given [T]: QC = ???
            |    def unseal(using qctx: QC): qctx.tasty.Tree = ???
            |    unseal.pos  // error
            |""".stripMargin
      }
      _ <- server.didSave("b/src/main/scala/b/B.scala")(identity)
      _ <- server.didChange("a/src/main/scala/a/A.scala") { _ =>
        """|object A:
           |  B.completionBar
           |  B.completionFoo
           |  B.completionUnknown
           |  B.completionAdded // should not errored out, as now we do not recompile A.scala
           |""".stripMargin
      }
      _ <- server.didSave("a/src/main/scala/a/A.scala")(identity)
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/scala/a/A.scala:4:3: error: value completionUnknown is not a member of object B
           |  B.completionUnknown
           |  ^^^^^^^^^^^^^^^^^^^
           |b/src/main/scala/b/B.scala:2:28: error: Found:    ("error" : String)
           |Required: Int
           |  def completionFoo: Int = "error"
           |                           ^^^^^^^
           |b/src/main/scala/b/B.scala:15:5: error: Failure to generate given instance for type ?{ pos: ? } from argument of type ?1.tasty.Tree)
           |
           |I found: <skolem>.tasty.pos(unseal(given_QC[Any]))
           |But the part corresponding to `<skolem>` is not a reference that can be generated.
           |This might be because resolution yielded as given instance a function that is not
           |known to be total and side-effect free.
           |
           |where:    ?1 is an unknown value of type B.QC
           |
           |    unseal.pos  // error
           |    ^^^^^^
           |""".stripMargin,
      )
      // there should be no "completionAdded" in completions
      // but the previous symbols should be there
      _ <- assertCompletion(
        "B.completion@@",
        """|completionBar: String
           |completionFoo: Int
        """.stripMargin,
      )
    } yield ()
  }
}
