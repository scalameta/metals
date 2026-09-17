package tests

import scala.concurrent.Future

import scala.meta.internal.metals.{BuildInfo => V}

import org.eclipse.lsp4j.InitializeResult

/**
 * Regression coverage for out-of-workspace fallback Scala CLI auto-start.
 *
 * `initialize(Map(folder -> layout))` registers only `workspace/<folder>` as
 * an LSP workspace folder. `writeLayout` without a folder name writes into
 * the test workspace root, which is the parent of that folder, so a path like
 * `/outsider/Foo.scala` is a sibling of the workspace folder, not inside it.
 *
 * `TestingServer.didOpen` awaits fallback `maybeImportFileAndLoad`, so
 * assertions after `didOpen` run after auto-start has been decided.
 */
class OutOfWorkspaceScalaCliLspSuite
    extends BaseLspSuite("out-of-workspace-scala-cli") {

  private def projectLayout: String =
    s"""|/metals.json
        |{
        |  "a": { "scalaVersion": "${V.scala213}" }
        |}
        |/a/src/main/scala/a/A.scala
        |package a
        |object A
        |""".stripMargin

  private def initializeFolder(
      folderName: String
  ): Future[InitializeResult] = {
    initialize(Map(folderName -> projectLayout), expectError = false)
  }

  private def assertWorkspaceFolder(
      folderName: String
  )(implicit loc: munit.Location): Unit = {
    assertEquals(
      server.fullServer.folderServices.map(_.path).toList,
      List(workspace.resolve(folderName)),
      s"LSP workspace folder should be workspace/$folderName, not the test root",
    )
  }

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
      _ <- initializeFolder("project")
      _ = assertWorkspaceFolder("project")
      // workspace/outsider is a sibling of workspace/project, the only LSP folder.
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
      _ <- initializeFolder("zipx")
      _ = assertWorkspaceFolder("zipx")
      // workspace/anode is a sibling of workspace/zipx (Cursor-style extra repo).
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

  test("empty-workspace-folders-still-auto-starts-scala-cli") {
    cleanWorkspace()
    writeLayout(
      s"""|/Orphan.scala
          |//> using scala ${V.scala213}
          |object Orphan {
          |  val i: Int = "boom"
          |}
          |""".stripMargin
    )
    for {
      _ <- initialize(Map.empty[String, String], expectError = false)
      _ <- server.didOpen("Orphan.scala")
      _ = assert(
        server.fullServer.fallbackService.scalaCli.servers.nonEmpty,
        "Empty workspace folders must keep legacy Scala CLI auto-start",
      )
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|Orphan.scala:3:16: error: type mismatch;
           | found   : String("boom")
           | required: Int
           |  val i: Int = "boom"
           |               ^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("outside-scala-script-does-not-start-scala-cli") {
    cleanWorkspace()
    for {
      _ <- initializeFolder("project")
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

  test("outside-java-does-not-start-scala-cli") {
    cleanWorkspace()
    for {
      _ <- initializeFolder("project")
      _ = writeLayout(
        """|/outsider/Main.java
           |class Main {}
           |""".stripMargin
      )
      _ <- server.didOpen("outsider/Main.java")
      _ = assertNoScalaCli(
        "Out-of-workspace Java files must not auto-start Scala CLI"
      )
    } yield ()
  }

  test("multiple-outside-files-do-not-start-scala-cli") {
    cleanWorkspace()
    for {
      _ <- initializeFolder("project")
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
      _ <- initializeFolder("project")
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
