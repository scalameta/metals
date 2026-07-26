package tests

import scala.meta.internal.metals.{BuildInfo => V}

/**
 * Regression tests for https://github.com/scalameta/metals/issues/8736
 *
 * When the client opens Scala files outside the LSP workspace folder(s), the
 * fallback service must not auto-start Scala CLI. Manual
 * `metals.scala-cli-start` remains unchanged (covered by ScalaCliSuite).
 *
 * `TestingServer.didOpen` awaits the fallback `maybeImportFileAndLoad` future,
 * so assertions after `didOpen` already run after auto-start has been decided
 * (and either skipped or completed).
 */
class OutOfWorkspaceScalaCliLspSuite
    extends BaseLspSuite("out-of-workspace-scala-cli") {

  test("didOpen-outside-workspace-does-not-start-scala-cli") {
    cleanWorkspace()
    for {
      _ <- initialize(
        Map(
          "project" ->
            s"""|/metals.json
                |{
                |  "a": { "scalaVersion": "${V.scala213}" }
                |}
                |/a/src/main/scala/a/A.scala
                |package a
                |object A
                |""".stripMargin
        ),
        expectError = false,
      )
      // Sibling of the workspace folder, still under the test root on disk.
      _ = writeLayout(
        """|/outsider/Foo.scala
           |object Foo {
           |  val x = 1
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("outsider/Foo.scala")
      _ = assertEquals(
        server.fullServer.fallbackService.scalaCli.servers.size,
        0,
        "Scala CLI must not auto-start for files outside workspace folders",
      )
      _ = assertEquals(
        server.fullServer.fallbackService.scalaCli.paths.toList,
        Nil,
      )
    } yield ()
  }

  test("sibling-under-common-parent-still-outside-workspace-folder") {
    // Mirrors multi-repo layouts (e.g. ~/projects/fun/{zipx,anode}): opening a
    // file in a sibling directory must not count as "in workspace".
    cleanWorkspace()
    for {
      _ <- initialize(
        Map(
          "zipx" ->
            s"""|/metals.json
                |{
                |  "a": { "scalaVersion": "${V.scala213}" }
                |}
                |/a/src/main/scala/a/A.scala
                |package a
                |object A
                |""".stripMargin
        ),
        expectError = false,
      )
      _ = writeLayout(
        """|/anode/src/Main.scala
           |object Main
           |""".stripMargin
      )
      _ <- server.didOpen("anode/src/Main.scala")
      _ = assertEquals(
        server.fullServer.fallbackService.scalaCli.servers.size,
        0,
        "Sibling repo under a common parent is still outside the workspace folder",
      )
    } yield ()
  }

  test("in-workspace-orphan-under-non-scala-folder-still-eligible") {
    // WorkspaceLspService must pass nonScalaProjects into the auto-start check
    // so orphans under a non-Scala workspace folder remain eligible.
    cleanWorkspace()
    for {
      _ <- initialize(
        Map(
          "docs" ->
            """|/README.md
               |Not a metals project yet.
               |""".stripMargin
        ),
        expectError = false,
      )
      _ = writeLayout(
        """|/Snippet.scala
           |object Snippet
           |""".stripMargin,
        "docs",
      )
      eligible = scala.meta.internal.metals.scalacli.ScalaCliAutoStart
        .shouldAutoStart(
          workspace.resolve("docs").resolve("Snippet.scala"),
          Seq(workspace.resolve("docs")),
        )
      _ = assertEquals(eligible, true)
    } yield ()
  }
}
