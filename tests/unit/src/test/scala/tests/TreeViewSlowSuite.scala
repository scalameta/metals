package tests

import scala.meta.internal.tvp.TreeViewProvider

object TreeViewSlowSuite extends BaseSlowSuite("tree-view") {
  testAsync("projects") {
    cleanWorkspace()
    for {
      _ <- server.initialize("""
                               |/metals.json
                               |{
                               |  "a": {},
                               |  "b": {}
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
        s"""|${TreeViewProvider.Build} <root>
            |${TreeViewProvider.Compile} <root>
            |""".stripMargin
      )
      _ = server.assertTreeViewChildren(
        s"projects:${server.buildTarget("a")}",
        ""
      )
      _ <- server.didOpen("a/src/main/scala/a/First.scala")
      _ <- server.didOpen("b/src/main/scala/b/Third.scala")
      _ = server.assertTreeViewChildren(
        s"projects:${server.buildTarget("a")}",
        "a/ +"
      )
      _ = server.assertTreeViewChildren(
        s"projects:${server.buildTarget("a")}!/a/",
        """|First class -
           |First object
           |Second class -
           |Second object
           |""".stripMargin
      )
      _ = server.assertTreeViewChildren(
        s"projects:${server.buildTarget("a")}!/a/First#",
        """|a() method
           |b val
           |""".stripMargin
      )
      _ = server.assertTreeViewChildren(
        s"projects:${server.buildTarget("a")}!/a/Second#",
        """|a() method
           |b val
           |c var
           |""".stripMargin
      )
    } yield ()
  }

  testAsync("libraries") {
    for {
      _ <- server.initialize(
        """
          |/metals.json
          |{
          |  "a": {
          |    "libraryDependencies": [
          |      "io.circe::circe-core:0.11.1",
          |      "org.eclipse.lsp4j:org.eclipse.lsp4j:0.5.0",
          |      "com.lihaoyi::sourcecode:0.1.7"
          |    ]
          |  }
          |}
          |""".stripMargin
      )
      _ = {
        server.assertTreeViewChildren(
          s"libraries:${server.jar("sourcecode")}",
          "sourcecode/ +"
        )
        server.assertTreeViewChildren(
          s"libraries:",
          """|animal-sniffer-annotations-1.17.jar -
             |cats-core_2.12-1.5.0.jar -
             |cats-kernel_2.12-1.5.0.jar -
             |cats-macros_2.12-1.5.0.jar -
             |charsets.jar -
             |checker-qual-2.5.2.jar -
             |circe-core_2.12-0.11.1.jar -
             |circe-numbers_2.12-0.11.1.jar -
             |error_prone_annotations-2.2.0.jar -
             |failureaccess-1.0.1.jar -
             |gson-2.7.jar -
             |guava-27.1-jre.jar -
             |j2objc-annotations-1.1.jar -
             |jce.jar -
             |jfr.jar -
             |jsr305-3.0.2.jar -
             |jsse.jar -
             |listenablefuture-9999.0-empty-to-avoid-conflict-with-guava.jar -
             |machinist_2.12-0.6.6.jar -
             |org.eclipse.lsp4j-0.5.0.jar -
             |org.eclipse.lsp4j.generator-0.5.0.jar -
             |org.eclipse.lsp4j.jsonrpc-0.5.0.jar -
             |org.eclipse.xtend.lib-2.19.0.M3.jar -
             |org.eclipse.xtend.lib.macro-2.19.0.M3.jar -
             |org.eclipse.xtext.xbase.lib-2.19.0.M3.jar -
             |resources.jar -
             |rt.jar -
             |scala-library-2.12.9.jar -
             |scala-reflect-2.12.9.jar -
             |sourcecode_2.12-0.1.7.jar -""".stripMargin
        )
        server.assertTreeViewChildren(
          s"libraries:${server.jar("scala-library")}!/scala/Some#",
          """|value val
             |isEmpty() method
             |get() method
             |x() method
             |""".stripMargin
        )
        server.assertTreeViewChildren(
          s"libraries:${server.jar("lsp4j")}!/org/eclipse/lsp4j/FileChangeType#",
          """|Created enum
             |Changed enum
             |Deleted enum
             |values() method
             |valueOf() method
             |getValue() method
             |forValue() method
             |""".stripMargin
        )
        server.assertTreeViewChildren(
          s"libraries:${server.jar("circe-core")}!/_root_/",
          """|io/ +
             |""".stripMargin
        )
        server.assertTreeViewChildren(
          s"libraries:${server.jar("cats-core")}!/cats/instances/symbol/",
          """|package object
             |""".stripMargin
        )
        assertNoDiff(
          server.workspaceSymbol("sourcecode.File", includeKind = true),
          """|sourcecode.File Class
             |sourcecode.File Object
             |sourcecode.FileMacros Interface
             |""".stripMargin
        )
        assertNoDiff(
          server.workspaceSymbol("lsp4j.LanguageClient", includeKind = true),
          """|org.eclipse.lsp4j.services.LanguageClient Interface
             |org.eclipse.lsp4j.services.LanguageClientAware Interface
             |org.eclipse.lsp4j.services.LanguageClientExtensions Interface
             |""".stripMargin
        )
        assertNoDiff(
          server.treeViewReveal(
            "sourcecode/SourceContext.scala",
            "object File",
            isIgnored = { label =>
              label.endsWith(".jar") &&
              !label.contains("sourcecode")
            }
          ),
          """|root
             |  Import build command
             |  Connect to build server command
             |  Projects (0)
             |  Libraries (30)
             |  Libraries (30)
             |    sourcecode_2.12-0.1.7.jar
             |    sourcecode_2.12-0.1.7.jar
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
             |""".stripMargin
        )
        assertNoDiff(
          server.treeViewReveal(
            "org/eclipse/lsp4j/services/LanguageClient.java",
            "registerCapability",
            isIgnored = { label =>
              label.endsWith(".jar") &&
              !label.contains("lsp4j")
            }
          ),
          """|root
             |  Import build command
             |  Connect to build server command
             |  Projects (0)
             |  Libraries (30)
             |  Libraries (30)
             |    org.eclipse.lsp4j-0.5.0.jar
             |    org.eclipse.lsp4j.generator-0.5.0.jar
             |    org.eclipse.lsp4j.jsonrpc-0.5.0.jar
             |    org.eclipse.lsp4j-0.5.0.jar
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
             |            InitializeErrorCode class
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
             |              LanguageClient class
             |              LanguageClientAware class
             |              LanguageClientExtensions class
             |              LanguageServer class
             |              TextDocumentService class
             |              WorkspaceService class
             |              LanguageClient class
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
             |""".stripMargin
        )
      }
    } yield ()
  }
}
