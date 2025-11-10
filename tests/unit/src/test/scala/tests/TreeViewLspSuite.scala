package tests

import java.nio.file.Paths

import scala.collection.SortedSet
import scala.util.Properties

import scala.meta.internal.metals.InitializationOptions
import scala.meta.internal.metals.JdkVersion
import scala.meta.internal.metals.UserConfiguration
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

  override def userConfig: UserConfiguration =
    UserConfiguration(javaHome = Some(Properties.javaHome))

  /**
   * The libraries we expect to find for tests in this file.
   */
  val expectedLibraries: SortedSet[String] = SortedSet(
    "cats-core_2.13",
    "cats-kernel_2.13",
    "circe-core_2.13",
    "circe-numbers_2.13",
    "error_prone_annotations",
    "failureaccess",
    "gson",
    "guava",
    "j2objc-annotations",
    jdkSourcesName,
    "jspecify",
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
        if (s != jdkSourcesName) s"${s}.jar package -"
        else s"$jdkSourcesName package -"
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
                        |/a/src/main/java/a/JavaClass.java
                        |package a;
                        |public class JavaClass {
                        | String name;
                        | String surname;
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
            |""".stripMargin,
      )
      _ <- server.didOpen("a/src/main/scala/a/Zero.scala")
      _ <- server.didSave("a/src/main/scala/a/Zero.scala")
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
        """|_empty_/ symbol-folder -
           |a/ symbol-folder -
           |""".stripMargin,
      )
      _ = server.assertTreeViewChildren(
        s"projects-$folder:${server.buildTarget("a")}!/_empty_/",
        """|Zero symbol-class +
           |""".stripMargin,
      )
      _ = server.assertTreeViewChildren(
        s"projects-$folder:${server.buildTarget("a")}!/_empty_/Zero#",
        """|a symbol-field
           |""".stripMargin,
      )
      _ = server.assertTreeViewChildren(
        s"projects-$folder:${server.buildTarget("a")}!/a/",
        """|First symbol-class -
           |First symbol-object
           |JavaClass symbol-class -
           |Second symbol-class -
           |Second symbol-object
           |""".stripMargin,
      )
      _ = server.assertTreeViewChildren(
        s"projects-$folder:${server.buildTarget("a")}!/a/First#",
        """|a() symbol-method
           |b symbol-field
           |""".stripMargin,
      )
      _ = server.assertTreeViewChildren(
        s"projects-$folder:${server.buildTarget("a")}!/a/JavaClass#",
        """|name symbol-field
           |surname symbol-field
           |""".stripMargin,
      )
      _ = server.assertTreeViewChildren(
        s"projects-$folder:${server.buildTarget("a")}!/a/Second#",
        """|a() symbol-method
           |b symbol-field
           |c symbol-variable
           |""".stripMargin,
      )
      _ <- server.didChange("a/src/main/scala/a/Zero.scala") { text =>
        text.replace("val a = 1", "val a = 1\nval b = 1.0")
      }
      _ = assertEquals(
        server.client.workspaceTreeViewChanges,
        s"metalsPackages projects-$folder:${server.buildTarget("a")}!/_root_/",
      )
      _ = server.assertTreeViewChildren(
        s"projects-$folder:${server.buildTarget("a")}!/_empty_/Zero#",
        """|a symbol-field
           |b symbol-field
           |""".stripMargin,
      )
      _ <- server.treeViewNodeCollapseDidChange(
        TreeViewProvider.Project,
        s"projects-$folder:${server.buildTarget("a")}!/_root_/",
        isCollapsed = true,
      )
      _ <- server.didChange("a/src/main/scala/a/Zero.scala") { text =>
        text.replace("val a = 1", "val a = 1\nval c = 1.0")
      }
      _ <- server.didSave("a/src/main/scala/a/Zero.scala")
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
          "sourcecode/ symbol-folder +",
        )
        server.assertTreeViewChildren(
          s"libraries-$folder:",
          expectedLibrariesString,
        )
        server.assertTreeViewChildren(
          s"libraries-$folder:${server.jar("scala-library")}!/scala/Some#",
          """|value symbol-field
             |get() symbol-method
             |""".stripMargin,
        )
        server.assertTreeViewChildren(
          s"libraries-$folder:${server.jar("lsp4j-")}!/org/eclipse/lsp4j/FileChangeType#",
          """
            |Created symbol-enum-member
            |Changed symbol-enum-member
            |Deleted symbol-enum-member
            |value symbol-field
            |<init>() symbol-method
            |getValue() symbol-method
            |forValue() symbol-method
            |""".stripMargin,
        )
        server.assertTreeViewChildren(
          s"libraries-$folder:${server.jar("circe-core")}!/_root_/",
          """|io/ symbol-folder +
             |""".stripMargin,
        )
        server.assertTreeViewChildren(
          s"libraries-$folder:${server.jar("cats-core")}!/_root_/",
          """|cats/ symbol-folder +
             |""".stripMargin,
        )
        server.assertTreeViewChildren(
          s"libraries-$folder:${server.jar("cats-core")}!/cats/compat/",
          """|FoldableCompat symbol-object -
             |Seq symbol-object -
             |SortedSet symbol-object -
             |Vector symbol-object -
             |""".stripMargin,
        )
        server.assertTreeViewChildren(
          s"libraries-$folder:${server.jar("cats-core")}!/cats/instances/symbol/",
          """|package symbol-object
             |""".stripMargin,
        )
        assertNoDiff(
          server.workspaceSymbol("Paths", includeKind = true),
          """|java.nio.file.Paths Class
             |""".stripMargin,
        )
        assertNoDiff(
          server.treeViewReveal(
            "java.base/java/nio/file/Paths.java",
            "class Paths",
          ),
          s"""|root
              |  Libraries (21) library
              |    $jdkSourcesName package
              |      java/ symbol-folder
              |        nio/ symbol-folder
              |          file/ symbol-folder
              |            Paths symbol-class
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
              label.endsWith(".jar package") &&
              !label.contains("sourcecode")
            },
          ),
          s"""|root
              |  Projects (0) project
              |  Libraries (21) library
              |  Libraries (21) library
              |    $jdkSourcesName package
              |    sourcecode_2.13-0.1.7-sources.jar package
              |    sourcecode_2.13-0.1.7-sources.jar package
              |      sourcecode/ symbol-folder
              |      sourcecode/ symbol-folder
              |        Args symbol-class
              |        Args symbol-object
              |        ArgsMacros symbol-interface
              |        Compat symbol-object
              |        Enclosing symbol-class
              |        Enclosing symbol-object
              |        EnclosingMachineMacros symbol-interface
              |        EnclosingMacros symbol-interface
              |        File symbol-class
              |        File symbol-object
              |        FileMacros symbol-interface
              |        FullName symbol-class
              |        FullName symbol-object
              |        FullNameMachineMacros symbol-interface
              |        FullNameMacros symbol-interface
              |        Line symbol-class
              |        Line symbol-object
              |        LineMacros symbol-interface
              |        Macros symbol-object
              |        Name symbol-class
              |        Name symbol-object
              |        NameMachineMacros symbol-interface
              |        NameMacros symbol-interface
              |        Pkg symbol-class
              |        Pkg symbol-object
              |        PkgMacros symbol-interface
              |        SourceCompanion symbol-class
              |        SourceValue symbol-class
              |        Text symbol-class
              |        Text symbol-object
              |        TextMacros symbol-interface
              |        Util symbol-object
              |        File symbol-object
              |""".stripMargin,
        )
        assertNoDiff(
          server.treeViewReveal(
            "org/eclipse/lsp4j/services/LanguageClient.java",
            "registerCapability",
            isIgnored = { label =>
              label.endsWith(".jar package") &&
              !label.contains("lsp4j-0")
            },
          ),
          s"""|root
              |  Projects (0) project
              |  Libraries (${expectedLibrariesCount}) library
              |  Libraries (${expectedLibrariesCount}) library
              |    $jdkSourcesName package
              |    org.eclipse.lsp4j-0.5.0-sources.jar package
              |    org.eclipse.lsp4j-0.5.0-sources.jar package
              |      org/ symbol-folder
              |      org/ symbol-folder
              |        eclipse/ symbol-folder
              |        eclipse/ symbol-folder
              |          lsp4j/ symbol-folder
              |          lsp4j/ symbol-folder
              |            adapters/ symbol-folder
              |            launch/ symbol-folder
              |            services/ symbol-folder
              |            util/ symbol-folder
              |            ApplyWorkspaceEditParams symbol-class
              |            ApplyWorkspaceEditResponse symbol-class
              |            ClientCapabilities symbol-class
              |            CodeAction symbol-class
              |            CodeActionCapabilities symbol-class
              |            CodeActionContext symbol-class
              |            CodeActionKind symbol-class
              |            CodeActionKindCapabilities symbol-class
              |            CodeActionLiteralSupportCapabilities symbol-class
              |            CodeActionParams symbol-class
              |            CodeLens symbol-class
              |            CodeLensCapabilities symbol-class
              |            CodeLensOptions symbol-class
              |            CodeLensParams symbol-class
              |            CodeLensRegistrationOptions symbol-class
              |            Color symbol-class
              |            ColorInformation symbol-class
              |            ColorPresentation symbol-class
              |            ColorPresentationParams symbol-class
              |            ColorProviderCapabilities symbol-class
              |            ColorProviderOptions symbol-class
              |            ColoringInformation symbol-class
              |            ColoringParams symbol-class
              |            ColoringStyle symbol-class
              |            Command symbol-class
              |            CompletionCapabilities symbol-class
              |            CompletionContext symbol-class
              |            CompletionItem symbol-class
              |            CompletionItemCapabilities symbol-class
              |            CompletionItemKind symbol-enum
              |            CompletionItemKindCapabilities symbol-class
              |            CompletionList symbol-class
              |            CompletionOptions symbol-class
              |            CompletionParams symbol-class
              |            CompletionRegistrationOptions symbol-class
              |            CompletionTriggerKind symbol-enum
              |            ConfigurationItem symbol-class
              |            ConfigurationParams symbol-class
              |            DefinitionCapabilities symbol-class
              |            Diagnostic symbol-class
              |            DiagnosticRelatedInformation symbol-class
              |            DiagnosticSeverity symbol-enum
              |            DidChangeConfigurationCapabilities symbol-class
              |            DidChangeConfigurationParams symbol-class
              |            DidChangeTextDocumentParams symbol-class
              |            DidChangeWatchedFilesCapabilities symbol-class
              |            DidChangeWatchedFilesParams symbol-class
              |            DidChangeWatchedFilesRegistrationOptions symbol-class
              |            DidChangeWorkspaceFoldersParams symbol-class
              |            DidCloseTextDocumentParams symbol-class
              |            DidOpenTextDocumentParams symbol-class
              |            DidSaveTextDocumentParams symbol-class
              |            DocumentColorParams symbol-class
              |            DocumentFilter symbol-class
              |            DocumentFormattingParams symbol-class
              |            DocumentHighlight symbol-class
              |            DocumentHighlightCapabilities symbol-class
              |            DocumentHighlightKind symbol-enum
              |            DocumentLink symbol-class
              |            DocumentLinkCapabilities symbol-class
              |            DocumentLinkOptions symbol-class
              |            DocumentLinkParams symbol-class
              |            DocumentLinkRegistrationOptions symbol-class
              |            DocumentOnTypeFormattingOptions symbol-class
              |            DocumentOnTypeFormattingParams symbol-class
              |            DocumentOnTypeFormattingRegistrationOptions symbol-class
              |            DocumentRangeFormattingParams symbol-class
              |            DocumentSymbol symbol-class
              |            DocumentSymbolCapabilities symbol-class
              |            DocumentSymbolParams symbol-class
              |            DynamicRegistrationCapabilities symbol-class
              |            ExecuteCommandCapabilities symbol-class
              |            ExecuteCommandOptions symbol-class
              |            ExecuteCommandParams symbol-class
              |            ExecuteCommandRegistrationOptions symbol-class
              |            FileChangeType symbol-enum
              |            FileEvent symbol-class
              |            FileSystemWatcher symbol-class
              |            FoldingRange symbol-class
              |            FoldingRangeCapabilities symbol-class
              |            FoldingRangeKind symbol-class
              |            FoldingRangeProviderOptions symbol-class
              |            FoldingRangeRequestParams symbol-class
              |            FormattingCapabilities symbol-class
              |            FormattingOptions symbol-class
              |            Hover symbol-class
              |            HoverCapabilities symbol-class
              |            ImplementationCapabilities symbol-class
              |            InitializeError symbol-class
              |            InitializeErrorCode symbol-interface
              |            InitializeParams symbol-class
              |            InitializeResult symbol-class
              |            InitializedParams symbol-class
              |            InsertTextFormat symbol-enum
              |            Location symbol-class
              |            MarkedString symbol-class
              |            MarkupContent symbol-class
              |            MarkupKind symbol-class
              |            MessageActionItem symbol-class
              |            MessageParams symbol-class
              |            MessageType symbol-enum
              |            OnTypeFormattingCapabilities symbol-class
              |            ParameterInformation symbol-class
              |            Position symbol-class
              |            PublishDiagnosticsCapabilities symbol-class
              |            PublishDiagnosticsParams symbol-class
              |            Range symbol-class
              |            RangeFormattingCapabilities symbol-class
              |            ReferenceContext symbol-class
              |            ReferenceParams symbol-class
              |            ReferencesCapabilities symbol-class
              |            Registration symbol-class
              |            RegistrationParams symbol-class
              |            RenameCapabilities symbol-class
              |            RenameParams symbol-class
              |            ResourceChange symbol-class
              |            ResponseErrorCode symbol-enum
              |            SaveOptions symbol-class
              |            SemanticHighlightingCapabilities symbol-class
              |            SemanticHighlightingInformation symbol-class
              |            SemanticHighlightingParams symbol-class
              |            SemanticHighlightingServerCapabilities symbol-class
              |            ServerCapabilities symbol-class
              |            ShowMessageRequestParams symbol-class
              |            SignatureHelp symbol-class
              |            SignatureHelpCapabilities symbol-class
              |            SignatureHelpOptions symbol-class
              |            SignatureHelpRegistrationOptions symbol-class
              |            SignatureInformation symbol-class
              |            SignatureInformationCapabilities symbol-class
              |            StaticRegistrationOptions symbol-class
              |            SymbolCapabilities symbol-class
              |            SymbolInformation symbol-class
              |            SymbolKind symbol-enum
              |            SymbolKindCapabilities symbol-class
              |            SynchronizationCapabilities symbol-class
              |            TextDocumentChangeRegistrationOptions symbol-class
              |            TextDocumentClientCapabilities symbol-class
              |            TextDocumentContentChangeEvent symbol-class
              |            TextDocumentEdit symbol-class
              |            TextDocumentIdentifier symbol-class
              |            TextDocumentItem symbol-class
              |            TextDocumentPositionParams symbol-class
              |            TextDocumentRegistrationOptions symbol-class
              |            TextDocumentSaveReason symbol-enum
              |            TextDocumentSaveRegistrationOptions symbol-class
              |            TextDocumentSyncKind symbol-enum
              |            TextDocumentSyncOptions symbol-class
              |            TextEdit symbol-class
              |            TypeDefinitionCapabilities symbol-class
              |            Unregistration symbol-class
              |            UnregistrationParams symbol-class
              |            VersionedTextDocumentIdentifier symbol-class
              |            WatchKind symbol-class
              |            WillSaveTextDocumentParams symbol-class
              |            WorkspaceClientCapabilities symbol-class
              |            WorkspaceEdit symbol-class
              |            WorkspaceEditCapabilities symbol-class
              |            WorkspaceFolder symbol-class
              |            WorkspaceFoldersChangeEvent symbol-class
              |            WorkspaceFoldersOptions symbol-class
              |            WorkspaceServerCapabilities symbol-class
              |            WorkspaceSymbolParams symbol-class
              |            services/ symbol-folder
              |              LanguageClient symbol-interface
              |              LanguageClientAware symbol-interface
              |              LanguageClientExtensions symbol-interface
              |              LanguageServer symbol-interface
              |              TextDocumentService symbol-interface
              |              WorkspaceService symbol-interface
              |              LanguageClient symbol-interface
              |                applyEdit() symbol-method
              |                registerCapability() symbol-method
              |                unregisterCapability() symbol-method
              |                telemetryEvent() symbol-method
              |                publishDiagnostics() symbol-method
              |                showMessage() symbol-method
              |                showMessageRequest() symbol-method
              |                logMessage() symbol-method
              |                workspaceFolders() symbol-method
              |                configuration() symbol-method
              |                semanticHighlighting() symbol-method
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
