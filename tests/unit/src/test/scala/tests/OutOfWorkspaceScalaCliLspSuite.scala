package tests

import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.{BuildInfo => V}

/**
 * Regression coverage for out-of-workspace fallback Scala CLI auto-start.
 *
 * Enables `scalaCliEnabled` because Scala CLI is opt-in on main-v2.
 * `TestingServer.didOpen` awaits fallback `maybeImportFileAndLoad`, so
 * assertions after `didOpen` run after auto-start has been decided.
 */
class OutOfWorkspaceScalaCliLspSuite
    extends BaseLspSuite("out-of-workspace-scala-cli") {

  override def userConfig: UserConfiguration =
    super.userConfig.copy(scalaCliEnabled = true)

  private def assertNoScalaCli(
      clue: String
  )(implicit loc: munit.Location): Unit = {
    assertEquals(
      server.fullServer.fallbackService.scalaCli.servers.size,
      0,
      clue,
    )
    assertEquals(
      server.fullServer.fallbackService.scalaCli.paths.toList,
      Nil,
      clue,
    )
  }

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
      _ = writeLayout(
        """|/outsider/Foo.scala
           |object Foo {
           |  val x = 1
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("outsider/Foo.scala")
      _ = assertNoScalaCli(
        "Scala CLI must not auto-start for files outside workspace folders"
      )
      _ = assertEquals(
        client.diagnostics
          .get(workspace.resolve("outsider/Foo.scala"))
          .getOrElse(Nil),
        Nil,
        "Out-of-workspace didOpen must not publish Problems diagnostics",
      )
    } yield ()
  }

  test("sibling-under-common-parent-still-outside-workspace-folder") {
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
      _ = assertNoScalaCli(
        "Sibling repo under a common parent is still outside the workspace folder"
      )
    } yield ()
  }

  test("empty-workspace-folders-policy-allows-auto-start") {
    // Empty-folder Scala CLI auto-start hangs in this branch's LSP harness
    // (SingleFileSuite is ignored). Check the live empty folder list against
    // the auto-start policy instead.
    cleanWorkspace()
    writeLayout(
      s"""|/Orphan.scala
          |//> using scala ${V.scala213}
          |object Orphan
          |""".stripMargin
    )
    for {
      _ <- initialize(Map.empty[String, String], expectError = false)
      folders =
        server.fullServer.folderServices.map(_.path) ++
          server.fullServer.nonScalaProjects.map(_.path)
      _ = assertEquals(folders, Nil)
      _ = assertEquals(
        scala.meta.internal.metals.scalacli.ScalaCliAutoStart
          .shouldAutoStart(workspace.resolve("Orphan.scala"), folders),
        true,
      )
    } yield ()
  }

  test("outside-scala-script-does-not-start-scala-cli") {
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
      _ = writeLayout(
        """|/outsider/script.sc
           |println(1)
           |""".stripMargin
      )
      _ <- server.didOpen("outsider/script.sc")
      _ = assertNoScalaCli(
        "Out-of-workspace Scala scripts must not auto-start Scala CLI"
      )
    } yield ()
  }

  test("multiple-outside-files-do-not-start-scala-cli") {
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
      _ = writeLayout(
        """|/outsider/Foo.scala
           |object Foo
           |/outsider/Bar.scala
           |object Bar
           |/other/Baz.scala
           |object Baz
           |""".stripMargin
      )
      _ <- server.didOpen("outsider/Foo.scala")
      _ <- server.didOpen("outsider/Bar.scala")
      _ <- server.didOpen("other/Baz.scala")
      _ = assertNoScalaCli(
        "Repeated out-of-workspace didOpen must not spawn Scala CLI"
      )
    } yield ()
  }

  test("outside-non-scala-didOpen-is-ignored") {
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
      _ = writeLayout(
        """|/outsider/README.md
           |# docs
           |""".stripMargin
      )
      _ <- server.didOpen("outsider/README.md")
      _ = assertNoScalaCli(
        "Out-of-workspace non-Scala didOpen must not start Scala CLI"
      )
    } yield ()
  }
}
