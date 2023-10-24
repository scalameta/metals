package tests

import java.nio.file.Paths

import scala.collection.SortedSet
import scala.util.Properties

import scala.meta.internal.metals.InitializationOptions
import scala.meta.internal.metals.JdkVersion
import scala.meta.internal.tvp.TreeViewProvider
import scala.meta.io.AbsolutePath

/**
 * @note This suite will fail on openjdk8 < 262)
 *       due to https://mail.openjdk.java.net/pipermail/jdk8u-dev/2020-July/012143.html
 */
class TreeViewLspSuite extends BaseLspSuite("tree-view") {

  private val javaVersion =
    JdkVersion
      .fromReleaseFileString(AbsolutePath(Paths.get(Properties.javaHome)))
      .getOrElse("")
  private val jdkSourcesName = s"jdk-$javaVersion-sources"
  override protected def initializationOptions: Option[InitializationOptions] =
    Some(TestingServer.TestDefault)

  /**
   * The libraries we expect to find for tests in this file.
   */
  val expectedLibraries: SortedSet[String] = SortedSet(
    "cats-core_2.13",
    "cats-kernel_2.13",
    "checker-qual",
    "circe-core_2.13",
    "circe-numbers_2.13",
    "error_prone_annotations",
    "failureaccess",
    "gson",
    "guava",
    "j2objc-annotations",
    jdkSourcesName,
    "jsr305",
    "org.eclipse.lsp4j",
    "org.eclipse.lsp4j.generator",
    "org.eclipse.lsp4j.jsonrpc",
    "org.eclipse.xtend.lib",
    "org.eclipse.xtend.lib.macro",
    "org.eclipse.xtext.xbase.lib",
    "scala-library",
    "scala-reflect",
    "simulacrum-scalafix-annotations_2.13",
    "sourcecode_2.13",
  )

  lazy val expectedLibrariesString: String =
    (this.expectedLibraries.toVector
      .map { (s: String) =>
        if (s != jdkSourcesName) s"${s}.jar -" else s"$jdkSourcesName -"
      })
      .mkString("\n")

  lazy val expectedLibrariesCount: Int =
    this.expectedLibraries.size

  test("projects") {
    cleanWorkspace()
    for {
      _ <- initialize("""
                        |/metals.json
                        |{
                        |  "a": {},
                        |  "b": {}
                        |}
                        |/a/src/main/scala/a/Zero.scala
                        |class Zero {
                        | val a = 1
                        |}
                        |/a/src/main/scala/a/First.scala
                        |package a
                        |class First {
                        |  def a = 1
                        |  val b = 2
                        |}
                        |object First
                        |/a/src/main/scala/a/Second.scala
                        |package a
                        |class Second {
                        |  def a = 1
                        |  val b = 2
                        |  var c = 2
                        |}
                        |object Second
                        |/b/src/main/scala/b/Third.scala
                        |package b
                        |class Third
                        |object Third
                        |/b/src/main/scala/b/Fourth.scala
                        |package b
                        |class Fourth
                        |object Fourth
                        |""".stripMargin)
      _ = assertNoDiff(
        client.workspaceTreeViewChanges,
        s"""|${TreeViewProvider.Project} <root>
            |${TreeViewProvider.Build} <root>
            |${TreeViewProvider.Compile} <root>
            |""".stripMargin,
      )
      _ <- server.didOpen("a/src/main/scala/a/Zero.scala")
      _ <- server.didSave("a/src/main/scala/a/Zero.scala")(identity)
      _ <- server.treeViewVisibilityDidChange(
        TreeViewProvider.Project,
        isVisible = true,
      )
      folder = server.server.path
      _ <- server.treeViewNodeCollapseDidChange(
        TreeViewProvider.Project,
        s"projects-$folder:${server.buildTarget("a")}!/_root_/",
        isCollapsed = false,
      )
      _ = server.assertTreeViewChildren(
        s"projects-$folder:${server.buildTarget("a")}",
        """|_empty_/ -
           |a/ -
           |""".stripMargin,
      )
      _ = server.assertTreeViewChildren(
        s"projects-$folder:${server.buildTarget("a")}!/_empty_/",
        """|Zero class +
           |""".stripMargin,
      )
      _ = server.assertTreeViewChildren(
        s"projects-$folder:${server.buildTarget("a")}!/_empty_/Zero#",
        """|a val
           |""".stripMargin,
      )
      _ = server.assertTreeViewChildren(
        s"projects-$folder:${server.buildTarget("a")}!/a/",
        """|First class -
           |First object
           |Second class -
           |Second object
           |""".stripMargin,
      )
      _ = server.assertTreeViewChildren(
        s"projects-$folder:${server.buildTarget("a")}!/a/First#",
        """|b val
           |a() method
           |""".stripMargin,
      )
      _ = server.assertTreeViewChildren(
        s"projects-$folder:${server.buildTarget("a")}!/a/Second#",
        """|c var
           |b val
           |a() method
           |""".stripMargin,
      )
      _ <- server.didSave("a/src/main/scala/a/Zero.scala") { text =>
        text.replace("val a = 1", "val a = 1\nval b = 1.0")
      }
      _ = assertEquals(
        server.client.workspaceTreeViewChanges,
        s"metalsPackages projects-$folder:${server.buildTarget("a")}!/_root_/"
      )
      _ = server.assertTreeViewChildren(
        s"projects-$folder:${server.buildTarget("a")}!/_empty_/Zero#",
        """|a val
           |b val
           |""".stripMargin,
      )
      _ <- server.treeViewNodeCollapseDidChange(
        TreeViewProvider.Project,
        s"projects-$folder:${server.buildTarget("a")}!/_root_/",
        isCollapsed = true,
      )
      _ <- server.didSave("a/src/main/scala/a/Zero.scala") { text =>
        text.replace("val a = 1", "val a = 1\nval c = 1.0")
      }
      _ = assertEmpty(
        server.client.workspaceTreeViewChanges
      )
    } yield ()
  }

  test("libraries", withoutVirtualDocs = true) {
    for {
      _ <- initialize(
        """
          |/metals.json
          |{
          |  "a": {
          |    "libraryDependencies": [
          |      "io.circe::circe-core:0.14.0",
          |      "org.eclipse.lsp4j:org.eclipse.lsp4j:0.5.0",
          |      "com.lihaoyi::sourcecode:0.1.7"
          |    ]
          |  }
          |}
          |""".stripMargin
      )
      folder = server.server.path
      _ = {
        server.assertTreeViewChildren(
          s"libraries-$folder:${server.jar("sourcecode")}",
          "sourcecode/ +",
        )
        server.assertTreeViewChildren(
          s"libraries-$folder:",
          expectedLibrariesString,
        )
        server.assertTreeViewChildren(
          s"libraries-$folder:${server.jar("scala-library")}!/scala/Some#",
          """|value val
             |get() method
             |""".stripMargin,
        )
        server.assertTreeViewChildren(
          s"libraries-$folder:${server.jar("lsp4j-")}!/org/eclipse/lsp4j/FileChangeType#",
          """|getValue() method
             |forValue() method
             |<init>() method
             |Created enum
             |Changed enum
             |Deleted enum
             |value field
             |""".stripMargin,
        )
        server.assertTreeViewChildren(
          s"libraries-$folder:${server.jar("circe-core")}!/_root_/",
          """|io/ +
             |""".stripMargin,
        )
        server.assertTreeViewChildren(
          s"libraries-$folder:${server.jar("cats-core")}!/_root_/",
          """|cats/ +
             |""".stripMargin,
        )
        server.assertTreeViewChildren(
          s"libraries-$folder:${server.jar("cats-core")}!/cats/compat/",
          """|FoldableCompat object -
             |Seq object -
             |SortedSet object -
             |Vector object -
             |""".stripMargin,
        )
        server.assertTreeViewChildren(
          s"libraries-$folder:${server.jar("cats-core")}!/cats/instances/symbol/",
          """|package object
             |""".stripMargin,
        )
        assertNoDiff(
          server.workspaceSymbol("Paths", includeKind = true),
          """|java.nio.file.Paths Class
             |""".stripMargin,
        )
        val withBase = if (!isJava8) "java.base/" else ""
        assertNoDiff(
          server.treeViewReveal(
            withBase + "java/nio/file/Paths.java",
            "class Paths",
          ),
          s"""|root
              |  Libraries (22)
              |    $jdkSourcesName
              |      java/
              |        nio/
              |          file/
              |            Paths class
              |""".stripMargin,
        )
        assertNoDiff(
          server.workspaceSymbol("sourcecode.File", includeKind = true),
          """|sourcecode.File Class
             |sourcecode.File Object
             |sourcecode.FileMacros Interface
             |""".stripMargin,
        )
        assertNoDiff(
          server.workspaceSymbol("lsp4j.LanguageClient", includeKind = true),
          """|org.eclipse.lsp4j.services.LanguageClient Interface
             |org.eclipse.lsp4j.services.LanguageClientAware Interface
             |org.eclipse.lsp4j.services.LanguageClientExtensions Interface
             |""".stripMargin,
        )
        assertNoDiff(
          server.treeViewReveal(
            "sourcecode/SourceContext.scala",
            "object File",
            isIgnored = { label =>
              label.endsWith(".jar") &&
              !label.contains("sourcecode")
            },
          ),
          s"""|root
              |  Projects (0)
              |  Libraries (22)
              |  Libraries (22)
              |    $jdkSourcesName
              |    sourcecode_2.13-0.1.7-sources.jar
              |    sourcecode_2.13-0.1.7-sources.jar
              |      sourcecode/
              |      sourcecode/
              |        Args class
              |        Args object
              |        ArgsMacros trait
              |        Compat object
              |        Enclosing class
              |        Enclosing object
              |        EnclosingMachineMacros trait
              |        EnclosingMacros trait
              |        File class
              |        File object
              |        FileMacros trait
              |        FullName class
              |        FullName object
              |        FullNameMachineMacros trait
              |        FullNameMacros trait
              |        Line class
              |        Line object
              |        LineMacros trait
              |        Macros object
              |        Name class
              |        Name object
              |        NameMachineMacros trait
              |        NameMacros trait
              |        Pkg class
              |        Pkg object
              |        PkgMacros trait
              |        SourceCompanion class
              |        SourceValue class
              |        Text class
              |        Text object
              |        TextMacros trait
              |        Util object
              |        File class
              |          value val
              |""".stripMargin,
        )
        assertNoDiff(
          server.treeViewReveal(
            "org/eclipse/lsp4j/services/LanguageClient.java",
            "registerCapability",
            isIgnored = { label =>
              label.endsWith(".jar") &&
              !label.contains("lsp4j-0")
            },
          ),
          s"""|root
              |  Projects (0)
              |  Libraries (${expectedLibrariesCount})
              |  Libraries (${expectedLibrariesCount})
              |    $jdkSourcesName
              |    org.eclipse.lsp4j-0.5.0-sources.jar
              |    org.eclipse.lsp4j-0.5.0-sources.jar
              |      org/
              |      org/
              |        eclipse/
              |        eclipse/
              |          lsp4j/
              |          lsp4j/
              |            adapters/
              |            launch/
              |            services/
              |            util/
              |            ApplyWorkspaceEditParams class
              |            ApplyWorkspaceEditResponse class
              |            ClientCapabilities class
              |            CodeAction class
              |            CodeActionCapabilities class
              |            CodeActionContext class
              |            CodeActionKind class
              |            CodeActionKindCapabilities class
              |            CodeActionLiteralSupportCapabilities class
              |            CodeActionParams class
              |            CodeLens class
              |            CodeLensCapabilities class
              |            CodeLensOptions class
              |            CodeLensParams class
              |            CodeLensRegistrationOptions class
              |            Color class
              |            ColorInformation class
              |            ColorPresentation class
              |            ColorPresentationParams class
              |            ColorProviderCapabilities class
              |            ColorProviderOptions class
              |            ColoringInformation class
              |            ColoringParams class
              |            ColoringStyle class
              |            Command class
              |            CompletionCapabilities class
              |            CompletionContext class
              |            CompletionItem class
              |            CompletionItemCapabilities class
              |            CompletionItemKind class
              |            CompletionItemKindCapabilities class
              |            CompletionList class
              |            CompletionOptions class
              |            CompletionParams class
              |            CompletionRegistrationOptions class
              |            CompletionTriggerKind class
              |            ConfigurationItem class
              |            ConfigurationParams class
              |            DefinitionCapabilities class
              |            Diagnostic class
              |            DiagnosticRelatedInformation class
              |            DiagnosticSeverity class
              |            DidChangeConfigurationCapabilities class
              |            DidChangeConfigurationParams class
              |            DidChangeTextDocumentParams class
              |            DidChangeWatchedFilesCapabilities class
              |            DidChangeWatchedFilesParams class
              |            DidChangeWatchedFilesRegistrationOptions class
              |            DidChangeWorkspaceFoldersParams class
              |            DidCloseTextDocumentParams class
              |            DidOpenTextDocumentParams class
              |            DidSaveTextDocumentParams class
              |            DocumentColorParams class
              |            DocumentFilter class
              |            DocumentFormattingParams class
              |            DocumentHighlight class
              |            DocumentHighlightCapabilities class
              |            DocumentHighlightKind class
              |            DocumentLink class
              |            DocumentLinkCapabilities class
              |            DocumentLinkOptions class
              |            DocumentLinkParams class
              |            DocumentLinkRegistrationOptions class
              |            DocumentOnTypeFormattingOptions class
              |            DocumentOnTypeFormattingParams class
              |            DocumentOnTypeFormattingRegistrationOptions class
              |            DocumentRangeFormattingParams class
              |            DocumentSymbol class
              |            DocumentSymbolCapabilities class
              |            DocumentSymbolParams class
              |            DynamicRegistrationCapabilities class
              |            ExecuteCommandCapabilities class
              |            ExecuteCommandOptions class
              |            ExecuteCommandParams class
              |            ExecuteCommandRegistrationOptions class
              |            FileChangeType class
              |            FileEvent class
              |            FileSystemWatcher class
              |            FoldingRange class
              |            FoldingRangeCapabilities class
              |            FoldingRangeKind class
              |            FoldingRangeProviderOptions class
              |            FoldingRangeRequestParams class
              |            FormattingCapabilities class
              |            FormattingOptions class
              |            Hover class
              |            HoverCapabilities class
              |            ImplementationCapabilities class
              |            InitializeError class
              |            InitializeErrorCode interface
              |            InitializeParams class
              |            InitializeResult class
              |            InitializedParams class
              |            InsertTextFormat class
              |            Location class
              |            MarkedString class
              |            MarkupContent class
              |            MarkupKind class
              |            MessageActionItem class
              |            MessageParams class
              |            MessageType class
              |            OnTypeFormattingCapabilities class
              |            ParameterInformation class
              |            Position class
              |            PublishDiagnosticsCapabilities class
              |            PublishDiagnosticsParams class
              |            Range class
              |            RangeFormattingCapabilities class
              |            ReferenceContext class
              |            ReferenceParams class
              |            ReferencesCapabilities class
              |            Registration class
              |            RegistrationParams class
              |            RenameCapabilities class
              |            RenameParams class
              |            ResourceChange class
              |            ResponseErrorCode class
              |            SaveOptions class
              |            SemanticHighlightingCapabilities class
              |            SemanticHighlightingInformation class
              |            SemanticHighlightingParams class
              |            SemanticHighlightingServerCapabilities class
              |            ServerCapabilities class
              |            ShowMessageRequestParams class
              |            SignatureHelp class
              |            SignatureHelpCapabilities class
              |            SignatureHelpOptions class
              |            SignatureHelpRegistrationOptions class
              |            SignatureInformation class
              |            SignatureInformationCapabilities class
              |            StaticRegistrationOptions class
              |            SymbolCapabilities class
              |            SymbolInformation class
              |            SymbolKind class
              |            SymbolKindCapabilities class
              |            SynchronizationCapabilities class
              |            TextDocumentChangeRegistrationOptions class
              |            TextDocumentClientCapabilities class
              |            TextDocumentContentChangeEvent class
              |            TextDocumentEdit class
              |            TextDocumentIdentifier class
              |            TextDocumentItem class
              |            TextDocumentPositionParams class
              |            TextDocumentRegistrationOptions class
              |            TextDocumentSaveReason class
              |            TextDocumentSaveRegistrationOptions class
              |            TextDocumentSyncKind class
              |            TextDocumentSyncOptions class
              |            TextEdit class
              |            TypeDefinitionCapabilities class
              |            Unregistration class
              |            UnregistrationParams class
              |            VersionedTextDocumentIdentifier class
              |            WatchKind class
              |            WillSaveTextDocumentParams class
              |            WorkspaceClientCapabilities class
              |            WorkspaceEdit class
              |            WorkspaceEditCapabilities class
              |            WorkspaceFolder class
              |            WorkspaceFoldersChangeEvent class
              |            WorkspaceFoldersOptions class
              |            WorkspaceServerCapabilities class
              |            WorkspaceSymbolParams class
              |            services/
              |              LanguageClient interface
              |              LanguageClientAware interface
              |              LanguageClientExtensions interface
              |              LanguageServer interface
              |              TextDocumentService interface
              |              WorkspaceService interface
              |              LanguageClient interface
              |                applyEdit() method
              |                registerCapability() method
              |                unregisterCapability() method
              |                telemetryEvent() method
              |                publishDiagnostics() method
              |                showMessage() method
              |                showMessageRequest() method
              |                logMessage() method
              |                workspaceFolders() method
              |                configuration() method
              |                semanticHighlighting() method
              |""".stripMargin,
        )
      }
    } yield ()
  }

  // see https://github.com/scalameta/metals/issues/846
  val noOp = "no-op"
  test(noOp.flaky) {
    cleanWorkspace()
    for {
      _ <- initialize(
        """
          |/metals.json
          |{
          |  "a": {},
          |  "b": {}
          |}
          |/a/src/main/scala/a/First.scala
          |package a
          |class First
          |/b/src/main/scala/b/Second.scala
          |package b
          |class Second {
          |  def a = 1
          |  val b = 2
          |}
          |""".stripMargin
      )
      folder = server.server.path
      // Trigger a compilation of Second.scala
      _ <- server.didOpen("b/src/main/scala/b/Second.scala")
      _ = server.assertTreeViewChildren(
        s"projects-$folder:${server.buildTarget("b")}",
        "b/ +",
      )

      // shutdown and restart a new LSP server
      _ = {
        cancelServer()
        newServer(noOp)
      }
      _ <- initialize("")

      // This request triggers a no-op background compilation in the "b" project
      // but immediately returns an empty result since the class directory
      // contains no `*.class` files yet. This is Bloop-specific behavior
      // and may be not an issue with other clients.
      _ = server.assertTreeViewChildren(
        s"projects-$folder:${server.buildTarget("b")}",
        "",
      )

      // Trigger a compilation in an unrelated project to ensure that the
      // background compilation of project "b" has completed.
      _ <- server.didOpen("a/src/main/scala/a/First.scala")

      // Assert that the tree view for "b" has been updated due to the triggered
      // background compilation of project "b" has completed, even if it was a
      // no-op.
      _ = server.assertTreeViewChildren(
        s"projects-$folder:${server.buildTarget("b")}",
        "b/ +",
      )

    } yield ()
  }
}
