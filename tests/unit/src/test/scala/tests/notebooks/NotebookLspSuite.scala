package tests.notebooks

import org.eclipse.lsp4j as l
import tests.{BaseLspSuite, QuickBuild, TestHovers}

import java.util as ju
import scala.concurrent.Future
import scala.meta.internal.metals.MetalsEnrichments.*
import scala.meta.internal.metals.{
  HoverExtParams,
  NotebookProvider,
  ServerCommands,
}
import scala.meta.io.AbsolutePath

/**
 * End to end tests for basic notebook cell language support
 * (https://github.com/scalameta/metals-feature-requests/issues/236),
 * driving `notebookDocument` and `textDocument` notifications/requests
 * directly rather than through a real VS Code + metals-vscode integration.
 */
class NotebookLspSuite extends BaseLspSuite("notebooks") {

  // Deliberately NOT nested under any build target's own source root (e.g.
  // `a/src/main/scala/a/...`): `TargetData.sourceBuildTargets` matches by
  // path *prefix*, so a notebook physically inside a target's source tree
  // would accidentally resolve to that target's real classpath via the
  // target's own, normal BSP-registered source root — regardless of whether
  // this file's own association mechanism ever runs — making tests that
  // check the "no/wrong association" case pass for the wrong reason.
  private val notebookPath = "Notebook.ipynb"

  private def ipynb: AbsolutePath = server.toPath(notebookPath)

  private def cellUri(id: String): String =
    s"${NotebookProvider.scheme}:${ipynb.toURI.getRawPath}#$id"

  private def cellPath(id: String): AbsolutePath =
    NotebookProvider.uriToPath(cellUri(id))

  private def openNotebook(cells: (String, String)*): Unit = {
    val notebookCells = cells.map { case (id, _) =>
      new l.NotebookCell(l.NotebookCellKind.Code, cellUri(id))
    }
    val notebookDocument = new l.NotebookDocument(
      ipynb.toURI.toString,
      "jupyter-notebook",
      1,
      notebookCells.asJava,
    )
    val cellTextDocuments = cells.map { case (id, text) =>
      new l.TextDocumentItem(cellUri(id), "scala", 1, text)
    }
    server.fullServer.notebookDidOpen(
      new l.DidOpenNotebookDocumentParams(
        notebookDocument,
        cellTextDocuments.asJava,
      )
    )
  }

  private def closeNotebook(): Unit =
    server.fullServer.notebookDidClose(
      new l.DidCloseNotebookDocumentParams(
        new l.NotebookDocumentIdentifier(ipynb.toURI.toString),
        ju.List.of(),
      )
    )

  private def changeCell(
      id: String,
      newText: String,
      version: Int = 2,
  ): Unit = {
    val textContent = new l.NotebookDocumentChangeEventCellTextContent(
      new l.VersionedTextDocumentIdentifier(cellUri(id), version),
      ju.List.of(new l.TextDocumentContentChangeEvent(newText)),
    )
    val cellsChange = new l.NotebookDocumentChangeEventCells()
    cellsChange.setTextContent(ju.List.of(textContent))
    val change = new l.NotebookDocumentChangeEvent()
    change.setCells(cellsChange)
    server.fullServer.notebookDidChange(
      new l.DidChangeNotebookDocumentParams(
        new l.VersionedNotebookDocumentIdentifier(2, ipynb.toURI.toString),
        change,
      )
    )
  }

  /** A `notebookDocument/didChange` with only a `cells.structure` change. */
  private def structureChange(
      start: Int,
      deleteCount: Int,
      inserted: List[(String, String)] = Nil,
      closed: List[String] = Nil,
  ): Unit = {
    val arrayChange = new l.NotebookCellArrayChange(
      start,
      deleteCount,
      inserted.map { case (id, _) =>
        new l.NotebookCell(l.NotebookCellKind.Code, cellUri(id))
      }.asJava,
    )
    val structure = new l.NotebookDocumentChangeEventCellStructure(arrayChange)
    if (inserted.nonEmpty)
      structure.setDidOpen(
        inserted.map { case (id, text) =>
          new l.TextDocumentItem(cellUri(id), "scala", 1, text)
        }.asJava
      )
    if (closed.nonEmpty)
      structure.setDidClose(
        closed.map(id => new l.TextDocumentIdentifier(cellUri(id))).asJava
      )
    val cellsChange = new l.NotebookDocumentChangeEventCells()
    cellsChange.setStructure(structure)
    val change = new l.NotebookDocumentChangeEvent()
    change.setCells(cellsChange)
    server.fullServer.notebookDidChange(
      new l.DidChangeNotebookDocumentParams(
        new l.VersionedNotebookDocumentIdentifier(2, ipynb.toURI.toString),
        change,
      )
    )
  }

  private def definitionAt(
      id: String,
      line: Int,
      character: Int,
  ): Future[java.util.List[l.Location]] =
    server.fullServer
      .definition(
        new l.TextDocumentPositionParams(
          new l.TextDocumentIdentifier(cellUri(id)),
          new l.Position(line, character),
        )
      )
      .asScala

  private def completionAt(
      id: String,
      line: Int,
      character: Int,
  ): Future[l.CompletionList] =
    server.fullServer
      .completion(
        new l.CompletionParams(
          new l.TextDocumentIdentifier(cellUri(id)),
          new l.Position(line, character),
        )
      )
      .asScala

  /**
   * The first `take` completion labels, alphabetically — enough to show the
   * actual completions a regression would change, without pinning down the
   * whole (long, version-sensitive) `List` API surface.
   */
  private def renderCompletionLabels(
      completions: l.CompletionList,
      take: Int = 5,
  ): String =
    completions.getItems.asScala
      .map(_.getLabel)
      .sorted
      .take(take)
      .mkString("\n")

  private def hoverAt(
      id: String,
      line: Int,
      character: Int,
      code: String,
  ): Future[String] =
    server.fullServer
      .hover(
        HoverExtParams(
          new l.TextDocumentIdentifier(cellUri(id)),
          new l.Position(line, character),
        )
      )
      .asScala
      .map(hover =>
        TestHovers.renderAsString(code, Option(hover), includeRange = false)
      )

  test("no-crash-on-didFocus") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/metals.json
            |{
            |  "a": {}
            |}
            |/$notebookPath
            |{}
            |""".stripMargin
      )
      _ = openNotebook("c1" -> "val x = 1")
      // Before this feature, this threw FileSystemNotFoundException (see
      // https://github.com/scalameta/metals-feature-requests/issues/236);
      // reaching this point at all is the assertion.
      _ <- server.fullServer.didFocus(cellUri("c1")).asScala
    } yield ()
  }

  test("cross-cell-completion-and-hover") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/metals.json
            |{
            |  "a": {}
            |}
            |/$notebookPath
            |{}
            |""".stripMargin
      )
      _ = openNotebook(
        "c1" -> "val xs = List(1, 2, 3)",
        "c2" -> "xs.",
      )
      completions <- completionAt("c2", 0, 3)
      _ = assertNoDiff(
        renderCompletionLabels(completions),
        """|+(other: String): String
           |++:[B >: Int](prefix: IterableOnce[B]): List[B]
           |++[B >: Int](suffix: IterableOnce[B]): List[B]
           |+:[B >: Int](elem: B): List[B]
           |/:[B](z: B)(op: (B, Int) => B): B""".stripMargin,
      )
      hover <- hoverAt("c2", 0, 1, "xs.")
      _ = assert(
        hover.contains("List[Int]"),
        s"expected hover to mention List[Int], got:\n$hover",
      )
    } yield ()
  }

  test("diagnostics-published-and-cleared-per-cell") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/metals.json
            |{
            |  "a": {}
            |}
            |/$notebookPath
            |{}
            |""".stripMargin
      )
      _ = openNotebook(
        "c1" -> "val xs = List(1, 2, 3)",
        "c2" -> "val y: Int = \"bad\"",
      )
      _ <- client.nextDiagnosticsFor(cellPath("c2"), _.nonEmpty)
      _ = assert(
        client.diagnostics(cellPath("c2")).exists { d =>
          d.getMessageAsString.contains("type mismatch") &&
          d.getRange.getStart.getLine == 0
        },
        s"unexpected c2 diagnostics: ${client.diagnostics(cellPath("c2"))}",
      )
      _ = assertEquals(
        client.diagnostics.getOrElse(cellPath("c1"), Seq.empty),
        Seq.empty,
      )
      _ = changeCell("c2", "val y: Int = 2")
      _ <- client.nextDiagnosticsFor(cellPath("c2"), _.isEmpty)
    } yield assertEquals(client.diagnostics(cellPath("c2")), Seq.empty)
  }

  test("completionItem-resolve-does-not-crash") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/metals.json
            |{
            |  "a": {}
            |}
            |/$notebookPath
            |{}
            |""".stripMargin
      )
      _ = openNotebook(
        "c1" -> "val xs = List(1, 2, 3)",
        "c2" -> "xs.",
      )
      completions <- completionAt("c2", 0, 3)
      item = completions.getItems.asScala
        .find(_.getLabel.startsWith("length"))
        .getOrElse(fail("no `length` completion for `xs.`"))
      resolved <- server.fullServer.completionItemResolve(item).asScala
    } yield assertEquals(resolved.getLabel, item.getLabel)
  }

  test("cell-removed-via-structure-change-loses-context-and-diagnostics") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/metals.json
            |{
            |  "a": {}
            |}
            |/$notebookPath
            |{}
            |""".stripMargin
      )
      _ = openNotebook(
        "c1" -> "val xs = List(1, 2, 3)",
        "c2" -> "val bad: Int = \"x\"",
        "c3" -> "xs.",
      )
      _ <- client.nextDiagnosticsFor(cellPath("c2"), _.nonEmpty)
      // remove c2 (index 1); c3 becomes the second cell, right after c1.
      // `clearDiagnostics` publishes synchronously as part of this call, so
      // (unlike a real recompute) there is no later event to await here.
      _ = structureChange(start = 1, deleteCount = 1, closed = List("c2"))
      // c2's stale diagnostic must be cleared, not left dangling
      _ = assertEquals(
        client.diagnostics.getOrElse(cellPath("c2"), Seq.empty),
        Seq.empty,
      )
      // c3 must still see `xs` from c1 despite the reshuffled offsets
      completions <- completionAt("c3", 0, 3)
      labels = completions.getItems.asScala.map(_.getLabel).toSet
    } yield assert(
      labels.exists(_.startsWith("length")),
      s"expected `length` among completions after removing c2, got: $labels",
    )
  }

  test("folding-range-and-semantic-tokens-do-not-crash") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/metals.json
            |{
            |  "a": {}
            |}
            |/$notebookPath
            |{}
            |""".stripMargin
      )
      _ = openNotebook(
        "c1" -> "val xs = List(1, 2, 3)",
        "c2" -> "xs.map(_ + 1)",
      )
      _ <- server.fullServer
        .foldingRange(
          new l.FoldingRangeRequestParams(
            new l.TextDocumentIdentifier(cellUri("c2"))
          )
        )
        .asScala
      tokens <- server.fullServer
        .semanticTokensFull(
          new l.SemanticTokensParams(
            new l.TextDocumentIdentifier(cellUri("c2"))
          )
        )
        .asScala
    } yield assert(
      tokens.getData.size() > 0,
      "expected some semantic tokens for a non-empty cell",
    )
  }

  test("markdown-and-other-language-cells-are-ignored") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/metals.json
            |{
            |  "a": {}
            |}
            |/$notebookPath
            |{}
            |""".stripMargin
      )
      // A real .ipynb almost always mixes markdown cells (and possibly other
      // languages) in with Scala code cells; those must not be spliced into
      // the concatenated script, or they'd break its compilation entirely.
      cellsMeta = List(
        l.NotebookCellKind.Markup -> "md",
        l.NotebookCellKind.Code -> "c1",
        l.NotebookCellKind.Code -> "py",
        l.NotebookCellKind.Code -> "c2",
      )
      notebookCells = cellsMeta.map { case (kind, id) =>
        new l.NotebookCell(kind, cellUri(id))
      }
      notebookDocument = new l.NotebookDocument(
        ipynb.toURI.toString,
        "jupyter-notebook",
        1,
        notebookCells.asJava,
      )
      cellTextDocuments = List(
        new l.TextDocumentItem(cellUri("md"), "markdown", 1, "# hello"),
        new l.TextDocumentItem(
          cellUri("c1"),
          "scala",
          1,
          "val xs = List(1, 2, 3)",
        ),
        new l.TextDocumentItem(cellUri("py"), "python", 1, "xs = [1, 2, 3]"),
        new l.TextDocumentItem(cellUri("c2"), "scala", 1, "xs."),
      )
      _ = server.fullServer.notebookDidOpen(
        new l.DidOpenNotebookDocumentParams(
          notebookDocument,
          cellTextDocuments.asJava,
        )
      )
      completions <- completionAt("c2", 0, 3)
    } yield assertNoDiff(
      renderCompletionLabels(completions),
      """|+(other: String): String
         |++:[B >: Int](prefix: IterableOnce[B]): List[B]
         |++[B >: Int](suffix: IterableOnce[B]): List[B]
         |+:[B >: Int](elem: B): List[B]
         |/:[B](z: B)(op: (B, Int) => B): B""".stripMargin,
    )
  }

  test("cross-cell-go-to-definition-points-at-the-right-cell") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/metals.json
            |{
            |  "a": {}
            |}
            |/$notebookPath
            |{}
            |""".stripMargin
      )
      _ = openNotebook(
        "c1" -> "def add(a: Int, b: Int): Int = a + b",
        "c2" -> "add(1, 2)",
      )
      locations <- definitionAt("c2", 0, 1)
    } yield {
      assert(!locations.isEmpty, "expected at least one definition location")
      val location = locations.get(0)
      assertEquals(
        location.getUri,
        cellUri("c1"),
        s"definition of `add` should point at c1, not the requesting cell c2 (got ${location.getUri})",
      )
      assertEquals(
        location.getRange.getStart.getLine,
        0,
        s"expected line 0 within c1 (its own local coordinates), got range ${location.getRange}",
      )
    }
  }

  test("documentSymbol-references-rename-callHierarchy-do-not-crash") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/metals.json
            |{
            |  "a": {}
            |}
            |/$notebookPath
            |{}
            |""".stripMargin
      )
      _ = openNotebook(
        "c1" -> "def add(a: Int, b: Int): Int = a + b",
        "c2" -> "add(1, 2)",
      )
      _ <- server.fullServer
        .documentSymbol(
          new l.DocumentSymbolParams(
            new l.TextDocumentIdentifier(cellUri("c1"))
          )
        )
        .asScala
      _ <- server.fullServer
        .references(
          new l.ReferenceParams(
            new l.TextDocumentIdentifier(cellUri("c1")),
            new l.Position(0, 5),
            new l.ReferenceContext(false),
          )
        )
        .asScala
      _ <- server.fullServer
        .prepareRename(
          new l.TextDocumentPositionParams(
            new l.TextDocumentIdentifier(cellUri("c1")),
            new l.Position(0, 5),
          )
        )
        .asScala
      _ <- server.fullServer
        .rename(
          new l.RenameParams(
            new l.TextDocumentIdentifier(cellUri("c1")),
            new l.Position(0, 5),
            "sum",
          )
        )
        .asScala
      _ <- server.fullServer
        .prepareCallHierarchy(
          new l.CallHierarchyPrepareParams(
            new l.TextDocumentIdentifier(cellUri("c1")),
            new l.Position(0, 5),
          )
        )
        .asScala
    } yield ()
  }

  test("bare-expression-cells-are-not-a-parse-error") {
    // A cell containing just an expression (`xs.length`, `println(xs)`, ...)
    // is completely ordinary notebook content, but is *not* itself legal at
    // the true top level of a Scala 3 source (unlike a `val`/`def`): Scala 3
    // allows multiple top-level *definitions*, not bare statements. Before
    // the concatenated script was wrapped in a throwaway `object`, this cell
    // alone produced a spurious "Illegal start of toplevel definition".
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/metals.json
            |{
            |  "a": {}
            |}
            |/$notebookPath
            |{}
            |""".stripMargin
      )
      _ = openNotebook(
        "c1" -> "val xs = List(1, 2, 3)",
        "c2" -> "xs.length",
      )
      _ <- client.nextDiagnosticsFor(cellPath("c2"))
    } yield assertEquals(
      client.diagnostics.getOrElse(cellPath("c2"), Seq.empty),
      Seq.empty,
    )
  }

  test("single-real-build-target-auto-associates") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/metals.json
            |{
            |  "a": {
            |    "libraryDependencies": ["io.circe::circe-generic:0.12.0"]
            |  }
            |}
            |/$notebookPath
            |{}
            |""".stripMargin
      )
      _ = openNotebook("c1" -> "val x: io.circe.Decoder[Int] = ???")
      _ <- client.nextDiagnosticsFor(cellPath("c1"))
    } yield assertEquals(
      client.diagnostics.getOrElse(cellPath("c1"), Seq.empty),
      Seq.empty,
      "the workspace's only build target should auto-associate, giving the cell circe on its classpath",
    )
  }

  test("multiple-build-targets-still-auto-associate") {
    // Exercises tryAutoAssociate's buildTargetsOrder-based pick, not the old
    // "exactly one candidate, otherwise ask the user" gate: both targets
    // carry the same extra dependency, so the assertion holds no matter
    // which of the two tryAutoAssociate actually picks.
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/metals.json
            |{
            |  "a": {
            |    "libraryDependencies": ["io.circe::circe-generic:0.12.0"]
            |  },
            |  "b": {
            |    "libraryDependencies": ["io.circe::circe-generic:0.12.0"]
            |  }
            |}
            |/$notebookPath
            |{}
            |""".stripMargin
      )
      _ = openNotebook("c1" -> "val x: io.circe.Decoder[Int] = ???")
      _ <- client.nextDiagnosticsFor(cellPath("c1"))
    } yield assertEquals(
      client.diagnostics.getOrElse(cellPath("c1"), Seq.empty),
      Seq.empty,
      "with two candidate build targets there's no longer a 'choose build " +
        "target' command to fall back on, so the notebook must still " +
        "auto-associate with one of them",
    )
  }

  test("didClose-clears-each-cell's-diagnostics") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/metals.json
            |{
            |  "a": {}
            |}
            |/$notebookPath
            |{}
            |""".stripMargin
      )
      _ = openNotebook(
        "c1" -> "val xs = List(1, 2, 3)",
        "c2" -> "val y: Int = \"bad\"",
      )
      _ <- client.nextDiagnosticsFor(cellPath("c2"), _.nonEmpty)
      _ = assert(
        client.diagnostics(cellPath("c2")).nonEmpty,
        "sanity check: c2 has a diagnostic while the notebook is open",
      )
      // forgetNotebook's clearDiagnostics publishes synchronously as part of
      // this call (same as structural cell removal elsewhere in this
      // suite), so there is no later event to await here.
      _ = closeNotebook()
    } yield assertEquals(
      client.diagnostics.getOrElse(cellPath("c2"), Seq.empty),
      Seq.empty,
      "c2's stale diagnostic must be cleared once the notebook is closed",
    )
  }

  test("didChange-to-an-unregistered-cell-is-ignored") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/metals.json
            |{
            |  "a": {}
            |}
            |/$notebookPath
            |{}
            |""".stripMargin
      )
      cellsMeta = List(
        l.NotebookCellKind.Markup -> "md",
        l.NotebookCellKind.Code -> "c1",
        l.NotebookCellKind.Code -> "c2",
      )
      notebookCells = cellsMeta.map { case (kind, id) =>
        new l.NotebookCell(kind, cellUri(id))
      }
      notebookDocument = new l.NotebookDocument(
        ipynb.toURI.toString,
        "jupyter-notebook",
        1,
        notebookCells.asJava,
      )
      cellTextDocuments = List(
        new l.TextDocumentItem(cellUri("md"), "markdown", 1, "# hello"),
        new l.TextDocumentItem(
          cellUri("c1"),
          "scala",
          1,
          "val xs = List(1, 2, 3)",
        ),
        new l.TextDocumentItem(cellUri("c2"), "scala", 1, "xs."),
      )
      _ = server.fullServer.notebookDidOpen(
        new l.DidOpenNotebookDocumentParams(
          notebookDocument,
          cellTextDocuments.asJava,
        )
      )
      // The selector on the notebookDocument/didChange registration isn't a
      // runtime guard (see NotebookProvider.didChange): a client can still
      // send a text-content change for the markdown cell. If that ever got
      // spliced into the combined script, it would break its compilation
      // entirely and c2's completions below would come back empty/garbage.
      _ = changeCell("md", "# hello *garbage* {{{ not scala at all ]]]")
      completions <- completionAt("c2", 0, 3)
    } yield assertNoDiff(
      renderCompletionLabels(completions),
      """|+(other: String): String
         |++:[B >: Int](prefix: IterableOnce[B]): List[B]
         |++[B >: Int](suffix: IterableOnce[B]): List[B]
         |+:[B >: Int](elem: B): List[B]
         |/:[B](z: B)(op: (B, Int) => B): B""".stripMargin,
    )
  }

  test("cell-inserted-via-structure-change-gets-cross-cell-context") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/metals.json
            |{
            |  "a": {}
            |}
            |/$notebookPath
            |{}
            |""".stripMargin
      )
      _ = openNotebook(
        "c1" -> "val xs = List(1, 2, 3)",
        "c2" -> "xs.length",
      )
      _ <- client.nextDiagnosticsFor(cellPath("c2"))
      // Insert c3 between c1 and c2 (index 1), no deletion.
      _ = structureChange(
        start = 1,
        deleteCount = 0,
        inserted = List("c3" -> "xs."),
      )
      completions <- completionAt("c3", 0, 3)
    } yield assertNoDiff(
      renderCompletionLabels(completions),
      """|+(other: String): String
         |++:[B >: Int](prefix: IterableOnce[B]): List[B]
         |++[B >: Int](suffix: IterableOnce[B]): List[B]
         |+:[B >: Int](elem: B): List[B]
         |/:[B](z: B)(op: (B, Int) => B): B""".stripMargin,
    )
  }

  test("notebook-with-unresolvable-uri-does-not-crash") {
    // A brand new, never-saved-to-disk notebook has an `untitled:` uri that
    // NotebookProvider.uriToPath (or the general fallback for a non-cell
    // uri) can't turn into a real path. Before the toAbsolutePathSafe guard,
    // this threw inside didOpen instead of just dropping the notification;
    // reaching this point at all is the assertion.
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/metals.json
            |{
            |  "a": {}
            |}
            |""".stripMargin
      )
      untitledUri = "untitled:Untitled-1"
      notebookCells = List(
        new l.NotebookCell(l.NotebookCellKind.Code, s"$untitledUri#c1")
      )
      notebookDocument = new l.NotebookDocument(
        untitledUri,
        "jupyter-notebook",
        1,
        notebookCells.asJava,
      )
      cellTextDocuments = List(
        new l.TextDocumentItem(s"$untitledUri#c1", "scala", 1, "val x = 1")
      )
      _ = server.fullServer.notebookDidOpen(
        new l.DidOpenNotebookDocumentParams(
          notebookDocument,
          cellTextDocuments.asJava,
        )
      )
    } yield ()
  }

  test("cell-under-a-target's-source-root-prefers-that-target") {
    // tryAutoAssociate must prefer a location-compatible candidate from
    // BuildTargets.sourceBuildTargets (same preference inverseSources gives
    // regular files) over ranking every workspace target globally: a
    // notebook physically inside a's source tree must associate with a,
    // regardless of how a and b compare under buildTargetsOrder.
    cleanWorkspace()
    val nestedNotebookPath = "a/src/main/scala/Notebook.ipynb"
    def nestedIpynb: AbsolutePath = server.toPath(nestedNotebookPath)
    def nestedCellUri(id: String): String =
      s"${NotebookProvider.scheme}:${nestedIpynb.toURI.getRawPath}#$id"
    for {
      _ <- initialize(
        s"""|/metals.json
            |{
            |  "a": {
            |    "libraryDependencies": ["io.circe::circe-generic:0.12.0"]
            |  },
            |  "b": {}
            |}
            |/$nestedNotebookPath
            |{}
            |""".stripMargin
      )
      notebookCells = List(
        new l.NotebookCell(l.NotebookCellKind.Code, nestedCellUri("c1"))
      )
      notebookDocument = new l.NotebookDocument(
        nestedIpynb.toURI.toString,
        "jupyter-notebook",
        1,
        notebookCells.asJava,
      )
      cellTextDocuments = List(
        new l.TextDocumentItem(
          nestedCellUri("c1"),
          "scala",
          1,
          "val x: io.circe.Decoder[Int] = ???",
        )
      )
      _ = server.fullServer.notebookDidOpen(
        new l.DidOpenNotebookDocumentParams(
          notebookDocument,
          cellTextDocuments.asJava,
        )
      )
      nestedCellPath = NotebookProvider.uriToPath(nestedCellUri("c1"))
      _ <- client.nextDiagnosticsFor(nestedCellPath)
    } yield assertEquals(
      client.diagnostics.getOrElse(nestedCellPath, Seq.empty),
      Seq.empty,
      "a notebook nested under a's source root must associate with a " +
        "(which has circe), not b, regardless of buildTargetsOrder ranking",
    )
  }

  test("stale-association-is-replaced-after-a-build-reload") {
    // tryAutoAssociate must notice when its current association's target no
    // longer exists (e.g. after a build reload swapped it for a new one)
    // and re-pick, instead of leaving the notebook pinned to a dead target
    // id forever.
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/metals.json
            |{
            |  "a": {
            |    "libraryDependencies": ["io.circe::circe-generic:0.12.0"]
            |  }
            |}
            |/$notebookPath
            |{}
            |""".stripMargin
      )
      _ = openNotebook("c1" -> "val x: io.circe.Decoder[Int] = ???")
      _ <- client.nextDiagnosticsFor(cellPath("c1"))
      _ = assertEquals(
        client.diagnostics.getOrElse(cellPath("c1"), Seq.empty),
        Seq.empty,
        "sanity check: c1 auto-associates with a before the reload",
      )
      // Swap "a" for "b" under the hood, the same way a real build.sbt edit
      // + reimport would, then trigger the reconnect a real reload does.
      _ = writeLayout(
        s"""|/metals.json
            |{
            |  "b": {
            |    "libraryDependencies": ["io.circe::circe-generic:0.12.0"]
            |  }
            |}
            |""".stripMargin
      )
      _ = QuickBuild.bloopInstall(workspace)
      _ <- server.executeCommand(ServerCommands.RestartBuildServer)
      _ <- client.nextDiagnosticsFor(cellPath("c1"))
    } yield assertEquals(
      client.diagnostics.getOrElse(cellPath("c1"), Seq.empty),
      Seq.empty,
      "c1 must re-associate with b (which also has circe) once a is gone, " +
        "not stay silently pinned to the removed a",
    )
  }

}
