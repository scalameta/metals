package tests

import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util
import java.util.Collections
import java.util.concurrent.ScheduledExecutorService

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Properties
import scala.util.matching.Regex
import scala.{meta => m}

import scala.meta.Input
import scala.meta.internal.implementation.Supermethods.GoToSuperMethodParams
import scala.meta.internal.implementation.Supermethods.formatMethodSymbolForQuickPick
import scala.meta.internal.io.FileIO
import scala.meta.internal.io.PathIO
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.ClientCommands
import scala.meta.internal.metals.Debug
import scala.meta.internal.metals.DebugSession
import scala.meta.internal.metals.DebugUnresolvedMainClassParams
import scala.meta.internal.metals.DidFocusResult
import scala.meta.internal.metals.Directories
import scala.meta.internal.metals.InitializationOptions
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsLanguageServer
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.PositionSyntax._
import scala.meta.internal.metals.ProgressTicks
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.TextEdits
import scala.meta.internal.metals.Time
import scala.meta.internal.metals.Trees
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.WindowStateDidChangeParams
import scala.meta.internal.metals.debug.Stoppage
import scala.meta.internal.metals.debug.TestDebugger
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.semanticdb.Scala.Symbols
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.tvp.TreeViewChildrenParams
import scala.meta.internal.tvp.TreeViewProvider
import scala.meta.internal.{semanticdb => s}
import scala.meta.io.AbsolutePath
import scala.meta.io.RelativePath

import ch.epfl.scala.{bsp4j => b}
import com.google.gson.JsonElement
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.CodeLensParams
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.DocumentOnTypeFormattingParams
import org.eclipse.lsp4j.DocumentRangeFormattingParams
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.FoldingRangeCapabilities
import org.eclipse.lsp4j.FoldingRangeRequestParams
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.RenameFile
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.TextDocumentClientCapabilities
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.TextDocumentPositionParams
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceClientCapabilities
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.{lsp4j => l}
import tests.MetalsTestEnrichments._

/**
 * Wrapper around `MetalsLanguageServer` with helpers methods for testing purposes.
 *
 * - manages text synchronization, example didSave writes file contents to disk.
 * - pretty-prints results of textDocument/definition for readable multiline string diffing.
 *
 * This class is great for testing because we get `Future[T]` values back from JSON-RPC
 * notifications like didOpen and didSave so that we can run callbacks once asynchronous background
 * jobs complete (example: BSP compilation, source indexing). It is not possible to test the
 * language server the same way from a real editor client like VS Code because JSON-RPC
 * notifications are `Any => Unit`, they cannot respond.
 */
final class TestingServer(
    workspace: AbsolutePath,
    val client: TestingClient,
    buffers: Buffers,
    config: MetalsServerConfig,
    bspGlobalDirectories: List[AbsolutePath],
    sh: ScheduledExecutorService,
    time: Time,
    initializationOptions: Option[InitializationOptions]
)(implicit ex: ExecutionContextExecutorService) {
  import scala.meta.internal.metals.JsonParser._
  val server = new MetalsLanguageServer(
    ex,
    buffers = buffers,
    redirectSystemOut = false,
    initialConfig = config,
    progressTicks = ProgressTicks.none,
    bspGlobalDirectories = bspGlobalDirectories,
    sh = sh,
    time = time,
    // relying on the macOS/Windows file watchers causes flaky test failures.
    isReliableFileWatcher = Properties.isLinux
  )
  server.connectToLanguageClient(client)
  private val readonlySources = TrieMap.empty[String, AbsolutePath]
  def statusBarHistory: String = {
    // collect both published items in the client and pending items from the server.
    val all = List(
      server.statusBar.pendingItems,
      client.statusParams.asScala.map(_.text)
    ).flatten
    all.distinct.mkString("\n")
  }

  private def write(layout: String): Unit = {
    FileLayout.fromString(layout, root = workspace)
  }

  def workspaceSymbol(
      query: String,
      includeKind: Boolean = false,
      includeFilename: Boolean = false
  ): String = {
    val infos = server.workspaceSymbol(query)
    infos
      .map { info =>
        val kind =
          if (includeKind) s" ${info.getKind}"
          else ""
        val filename =
          if (includeFilename) {
            val path = Paths.get(URI.create(info.getLocation().getUri()))
            s" ${path.getFileName()}"
          } else ""
        val container = Option(info.getContainerName()).getOrElse("")
        s"${container}${info.getName}$kind$filename"
      }
      .mkString("\n")
  }
  def workspaceSources(): Seq[AbsolutePath] = {
    for {
      sourceItem <- server.buildTargets.sourceItems.toSeq
      if sourceItem.exists
      source <-
        if (sourceItem.isScalaOrJava)
          Seq(sourceItem)
        else FileIO.listAllFilesRecursively(sourceItem)
    } yield source
  }

  def buildTargetSourceJars(buildTarget: String): Future[Seq[String]] = {
    server.buildServer match {
      case Some(build) =>
        for {
          workspaceBuildTargets <- build.workspaceBuildTargets()
          ids =
            workspaceBuildTargets.getTargets
              .map(_.getId)
              .asScala
              .filter(_.getUri().contains(s"?id=$buildTarget"))
          dependencySources <-
            build
              .buildTargetDependencySources(
                new b.DependencySourcesParams(ids.asJava)
              )
        } yield {
          dependencySources
            .getItems()
            .asScala
            .flatMap(_.getSources().asScala)
        }
      case None =>
        Future.successful(Seq.empty)
    }
  }

  def assertGotoSuperMethod(
      asserts: Map[Int, Option[Int]],
      context: Map[Int, (l.Position, String)]
  )(implicit loc: munit.Location): Future[Unit] = {
    def exec(
        toCheck: List[(Int, Option[Int])]
    ): Future[List[Option[(l.Position, String)]]] = {
      toCheck match {
        case (pos, expectedPos) :: tl =>
          val (position, document) = context(pos)
          val command = GoToSuperMethodParams(document, position)
          executeCommand(ServerCommands.GotoSuperMethod.id, command)
            .flatMap(_ =>
              exec(tl).map(rest => expectedPos.flatMap(context.get) +: rest)
            )
        case _ =>
          Future.successful(List.empty)
      }
    }

    val resultsF = exec(asserts.toList)

    resultsF.map { expectedGotoPositionsOpts =>
      val expectedGotoPositions = expectedGotoPositionsOpts.collect {
        case Some(pos) => pos
      }
      val gotoExecutedCommandPositions = client.clientCommands.asScala
        .filter(_.getCommand == ClientCommands.GotoLocation.id)
        .map(_.getArguments.asScala.head.asInstanceOf[l.Location])
        .map(l => (l.getRange.getStart, l.getUri))
        .toList

      Assertions.assertEquals(
        gotoExecutedCommandPositions,
        expectedGotoPositions
      )
    }
  }

  def assertSuperMethodHierarchy(
      uri: String,
      expectations: List[(Int, List[String])],
      context: Map[Int, l.Position]
  )(implicit loc: munit.Location): Future[Unit] = {
    val obtained = scala.collection.mutable.Buffer[Set[String]]()
    client.showMessageRequestHandler = { req =>
      val titles = req.getActions.asScala
        .map(action => formatMethodSymbolForQuickPick(action.getTitle))
        .toSet
      obtained.append(titles)
      Some(req.getActions.get(0))
    }

    def exec(toCheck: List[(Int, List[String])]): Future[List[Set[String]]] = {
      toCheck match {
        case (pos, expected) :: tl =>
          val command = GoToSuperMethodParams(uri, context(pos))
          executeCommand(ServerCommands.SuperMethodHierarchy.id, command)
            .flatMap(_ => exec(tl).map(rest => expected.toSet +: rest))

        case _ =>
          Future.successful(List.empty)
      }
    }

    exec(expectations).map(expected => {
      Assertions.assertEquals(obtained.toList, expected)
    })
  }

  def assertReferences(
      filename: String,
      query: String,
      expected: Map[String, String],
      base: Map[String, String]
  )(implicit loc: munit.Location): Future[Unit] = {
    for {
      referenceLocations <- getReferenceLocations(filename, query, base)
    } yield {
      Assertions.assertSimpleLocationOrdering(referenceLocations)
      val references =
        TestRanges.renderLocationsAsString(base, referenceLocations)
      references.foreach {
        case (file, obtained) =>
          val expectedImpl = expected(file)
          Assertions.assertNoDiff(
            obtained,
            expectedImpl
          )
      }
    }
  }

  def assertReferenceDefinitionBijection()(implicit
      loc: munit.Location
  ): Unit = {
    val compare = workspaceReferences()
    assert(compare.definition.nonEmpty)
    assert(compare.references.nonEmpty)
    Assertions.assertNoDiff(
      compare.referencesFormat,
      compare.definitionFormat
    )
  }

  def assertReferenceDefinitionDiff(
      expectedDiff: String
  )(implicit loc: munit.Location): Unit = {
    Assertions.assertNoDiff(
      workspaceReferences().diff,
      expectedDiff
    )
  }
  def workspaceReferences(): WorkspaceSymbolReferences = {
    val inverse =
      mutable.Map.empty[SymbolReference, mutable.ListBuffer[Location]]
    val inputsCache = mutable.Map.empty[String, Input]
    def readInput(uri: String): Input = {
      inputsCache.getOrElseUpdate(
        uri, {
          val path = uri.toAbsolutePath
          path
            .toInputFromBuffers(buffers)
            .copy(path = path.toRelative(workspace).toURI(false).toString)
        }
      )
    }
    def newRef(symbol: String, loc: Location): SymbolReference =
      SymbolReference(symbol, loc, loc.getRange.toMeta(readInput(loc.getUri)))
    for {
      source <- workspaceSources()
      input = source.toInputFromBuffers(buffers)
      identifier = source.toTextDocumentIdentifier
      token <- Trees.defaultDialect(input).tokenize.get
      if token.isIdentifier
      params = token.toPositionParams(identifier)
      definition = server.definitionResult(params).asJava.get()
      if !definition.symbol.isPackage
      if !definition.definition.exists(_.isDependencySource(workspace))
      location <- definition.locations.asScala
    } {
      val buf = inverse.getOrElseUpdate(
        newRef(definition.symbol, location),
        mutable.ListBuffer.empty
      )
      buf += new Location(source.toURI.toString, token.pos.toLSP)
    }
    val definition = Seq.newBuilder[SymbolReference]
    val references = Seq.newBuilder[SymbolReference]
    for {
      (ref, expectedLocations) <- inverse.toSeq.sortBy(_._1.symbol)
    } {
      val params = new ReferenceParams(
        new TextDocumentIdentifier(
          ref.location.getUri
        ),
        ref.location.getRange.getStart,
        new ReferenceContext(true)
      )
      val obtainedLocations = server.referencesResult(params)
      references ++= obtainedLocations.locations.map(l =>
        newRef(obtainedLocations.symbol, l)
      )
      definition ++= expectedLocations.map(l => newRef(ref.symbol, l))
    }
    WorkspaceSymbolReferences(
      references.result().distinct,
      definition.result().distinct
    )
  }

  def initialize(
      layout: String,
      expectError: Boolean = false,
      preInitialized: () => Future[Unit] = () => Future.successful(()),
      workspaceFolders: List[String] = Nil
  ): Future[Unit] = {
    Debug.printEnclosing()
    write(layout)
    QuickBuild.bloopInstall(workspace)
    val params = new InitializeParams
    val workspaceCapabilities = new WorkspaceClientCapabilities()
    val textDocumentCapabilities = new TextDocumentClientCapabilities
    textDocumentCapabilities.setFoldingRange(new FoldingRangeCapabilities)
    val initOptions = initializationOptions.getOrElse(
      InitializationOptions.Default.copy(
        debuggingProvider = true,
        treeViewProvider = true,
        slowTaskProvider = true
      )
    )
    params.setCapabilities(
      new ClientCapabilities(
        workspaceCapabilities,
        textDocumentCapabilities,
        initOptions.toJson
      )
    )
    params.setWorkspaceFolders(
      workspaceFolders
        .map(file => new WorkspaceFolder(toPath(file).toURI.toString))
        .asJava
    )
    params.setRootUri(workspace.toURI.toString)
    for {
      _ <- server.initialize(params).asScala
      _ <- preInitialized()
      _ <- server.initialized(new InitializedParams).asScala
    } yield {
      if (!expectError) {
        assertBuildServerConnection()
      }
    }
  }

  def assertBuildServerConnection(): Unit = {
    require(server.buildServer.isDefined, "Build server did not initialize")
  }

  def toPath(filename: String): AbsolutePath =
    TestingServer.toPath(workspace, filename)

  def executeCommand(command: String, params: Object*): Future[Any] = {
    Debug.printEnclosing()
    scribe.info(s"Executing command [$command]")
    val args: java.util.List[Object] =
      params.map(_.toJson.asInstanceOf[Object]).asJava

    server.executeCommand(new ExecuteCommandParams(command, args)).asScala
  }

  def startDebugging(
      target: String,
      kind: String,
      parameter: AnyRef,
      stoppageHandler: Stoppage.Handler = Stoppage.Handler.Continue
  ): Future[TestDebugger] = {

    assertSystemExit(parameter)
    val targets = List(new b.BuildTargetIdentifier(buildTarget(target)))
    val params =
      new b.DebugSessionParams(targets.asJava, kind, parameter.toJson)

    executeCommand(ServerCommands.StartDebugAdapter.id, params).collect {
      case DebugSession(_, uri) =>
        scribe.info(s"Starting debug session for $uri")
        TestDebugger(URI.create(uri), stoppageHandler)
    }
  }

  // note(@tgodzik) all test should have `System.exit(0)` added to avoid occasional issue due to:
  // https://stackoverflow.com/questions/2225737/error-jdwp-unable-to-get-jni-1-2-environment
  private def assertSystemExit(parameter: AnyRef) = {
    def check() = {
      val workspaceFiles =
        workspace.listRecursive.filter(_.isScalaOrJava).toList
      val usesSystemExit =
        workspaceFiles.exists(_.text.contains("System.exit(0)"))
      if (!usesSystemExit)
        throw new RuntimeException(
          "All debug test for main classes should have `System.exit(0)`"
        )
    }

    parameter match {
      case _: b.ScalaMainClass =>
        check()
      case json: JsonElement =>
        val mainParams = json.as[DebugUnresolvedMainClassParams]
        val mainClass = mainParams.toOption
          .flatMap(main => Option(main.mainClass))
        if (mainClass.isDefined)
          check()
      case _ =>
    }
  }

  def startDebuggingUnresolved(
      params: AnyRef
  ): Future[TestDebugger] = {
    assertSystemExit(params)
    executeCommand(ServerCommands.StartDebugAdapter.id, params).collect {
      case DebugSession(_, uri) =>
        TestDebugger(URI.create(uri), Stoppage.Handler.Continue)
    }
  }

  def didFocus(filename: String): Future[DidFocusResult.Value] = {
    server.didFocus(toPath(filename).toURI.toString).asScala
  }

  def windowStateDidChange(focused: Boolean): Unit = {
    server.windowStateDidChange(WindowStateDidChangeParams(focused))
  }

  def didSave(filename: String)(fn: String => String): Future[Unit] = {
    Debug.printEnclosing()
    val abspath = toPath(filename)
    val oldText = abspath.toInputFromBuffers(buffers).text
    val newText = fn(oldText)
    Files.write(
      abspath.toNIO,
      newText.getBytes(StandardCharsets.UTF_8)
    )
    server
      .didSave(
        new DidSaveTextDocumentParams(
          new TextDocumentIdentifier(abspath.toURI.toString)
        )
      )
      .asScala
  }

  def didChange(filename: String)(fn: String => String): Future[Unit] = {
    Debug.printEnclosing()
    val abspath = toPath(filename)
    val oldText = abspath.toInputFromBuffers(buffers).text
    val newText = fn(oldText)
    server
      .didChange(
        new DidChangeTextDocumentParams(
          new VersionedTextDocumentIdentifier(abspath.toURI.toString, 0),
          Collections.singletonList(new TextDocumentContentChangeEvent(newText))
        )
      )
      .asScala
  }

  def didOpen(filename: String): Future[Unit] = {
    Debug.printEnclosing()
    val abspath = toPath(filename)
    val uri = abspath.toURI.toString
    val extension = PathIO.extension(abspath.toNIO)
    val text = abspath.readText
    server
      .didOpen(
        new DidOpenTextDocumentParams(
          new TextDocumentItem(uri, extension, 0, text)
        )
      )
      .asScala
  }

  def didClose(filename: String): Future[Unit] = {
    Debug.printEnclosing()
    val abspath = toPath(filename)
    val uri = abspath.toURI.toString
    Future.successful {
      server
        .didClose(
          new DidCloseTextDocumentParams(
            new TextDocumentIdentifier(uri)
          )
        )
    }
  }

  def didChangeConfiguration(config: String): Future[Unit] = {
    val json = UserConfiguration.parse(config)
    val params = new DidChangeConfigurationParams(json)
    server.didChangeConfiguration(params).asScala
  }

  def completionList(
      filename: String,
      query: String
  ): Future[CompletionList] = {
    val path = toPath(filename)
    val input = path.toInputFromBuffers(buffers)
    val offset = query.indexOf("@@")
    if (offset < 0) sys.error("missing @@")
    val start = input.text.indexOf(query.replace("@@", ""))
    if (start < 0)
      sys.error(s"missing query '$query' from text:\n${input.text}")
    val point = start + offset
    val pos = m.Position.Range(input, point, point)
    val params =
      new CompletionParams(path.toTextDocumentIdentifier, pos.toLSP.getStart)
    server.completion(params).asScala
  }

  def foldingRange(filename: String): Future[String] = {
    val path = toPath(filename)
    val uri = path.toURI.toString
    val params = new FoldingRangeRequestParams(new TextDocumentIdentifier(uri))
    for {
      ranges <- server.foldingRange(params).asScala
      textEdits = FoldingRangesTextEdits(ranges)
    } yield TextEdits.applyEdits(textContents(filename), textEdits)
  }

  def assertFolded(filename: String, expected: String)(implicit
      loc: munit.Location
  ): Future[Unit] =
    for {
      folded <- foldingRange(filename)
      _ = Assertions.assertNoDiff(folded, expected)
    } yield ()

  def onTypeFormatting(
      filename: String,
      query: String,
      expected: String,
      autoIndent: String,
      replaceWith: String,
      root: AbsolutePath = workspace
  )(implicit loc: munit.Location): Future[Unit] = {
    for {
      (text, params) <- onTypeParams(
        filename,
        query,
        root,
        autoIndent,
        replaceWith
      )
      multiline <- server.onTypeFormatting(params).asScala
      format = TextEdits.applyEdits(
        textContents(filename),
        multiline.asScala.toList
      )
    } yield {
      Assertions.assertNoDiff(format, expected)
    }
  }

  def rangeFormatting(
      filename: String,
      query: String,
      expected: String,
      paste: String,
      root: AbsolutePath
  )(implicit loc: munit.Location): Future[Unit] = {
    for {
      (_, params) <- rangeFormattingParams(filename, query, paste, root)
      multiline <- server.rangeFormatting(params).asScala
      format = TextEdits.applyEdits(
        textContents(filename),
        multiline.asScala.toList
      )
    } yield {
      Assertions.assertNoDiff(format, expected)
    }
  }
  def rangeFormatting(
      filename: String,
      query: String,
      expected: String,
      root: AbsolutePath = workspace
  )(implicit loc: munit.Location): Future[Unit] = {
    for {
      (_, params) <- rangeFormattingParams(filename, query, root)
      multiline <- server.rangeFormatting(params).asScala
      format = TextEdits.applyEdits(
        textContents(filename),
        multiline.asScala.toList
      )
    } yield {
      Assertions.assertNoDiff(format, expected)
    }
  }

  def codeLenses(filename: String)(maxRetries: Int): Future[String] = {
    val path = toPath(filename)
    val uri = path.toURI.toString
    val params = new CodeLensParams(new TextDocumentIdentifier(uri))

    // see https://github.com/scalacenter/bloop/issues/1067
    // because bloop does not notify us when we can access the main/test classes,
    // we have to try until we finally get them.
    // Following handler runs on the refresh-model notification from the server
    // (basically once the compilation finishes and classes are fetched)
    // it retries the compilation until we finally can get desired lenses
    // or fails if it could nat be achieved withing [[maxRetries]] number of tries
    var retries = maxRetries
    val codeLenses = Promise[List[l.CodeLens]]()
    val handler = { refreshCount: Int =>
      if (refreshCount > 0)
        for {
          lenses <- server.codeLens(params).asScala.map(_.asScala)
        } {
          if (lenses.nonEmpty) codeLenses.trySuccess(lenses.toList)
          else if (retries > 0) {
            retries -= 1
            server.compilations.compileFile(path)
          } else {
            val error = s"Could not fetch any code lenses in $maxRetries tries"
            codeLenses.tryFailure(new NoSuchElementException(error))
          }
        }
    }

    for {
      _ <-
        server
          .didFocus(uri)
          .asScala // model is refreshed only for focused document
      _ = client.refreshModelHandler = handler
      // first compilation, to trigger the handler
      _ <- server.compilations.compileFile(path)
      lenses <- codeLenses.future
      textEdits = CodeLensesTextEdits(lenses)
    } yield TextEdits.applyEdits(textContents(filename), textEdits.toList)
  }

  def formatCompletion(
      completion: CompletionList,
      includeDetail: Boolean,
      filter: String => Boolean = _ => true
  ): String = {
    val items =
      completion.getItems.asScala
        .map(item => server.completionItemResolve(item).get())
    items.iterator
      .filter(item => filter(item.getLabel()))
      .map { item =>
        val label = TestCompletions.getFullyQualifiedLabel(item)
        val shouldIncludeDetail = item.getDetail != null && includeDetail
        val detail =
          if (shouldIncludeDetail && !label.contains(item.getDetail))
            item.getDetail
          else ""
        label + detail
      }
      .mkString("\n")
  }

  private def positionFromString[T](
      filename: String,
      original: String,
      root: AbsolutePath,
      replaceWith: String = ""
  )(
      fn: (String, TextDocumentIdentifier, l.Position) => T
  ): Future[T] = {
    val offset = original.indexOf("@@")
    if (offset < 0) sys.error(s"missing @@\n$original")
    val text = original.replace("@@", replaceWith)
    val input = m.Input.String(text)
    val path = root.resolve(filename)
    path.touch()
    val pos = m.Position.Range(input, offset, offset)
    for {
      _ <- didChange(filename)(_ => text)
    } yield {
      fn(
        text,
        path.toTextDocumentIdentifier,
        pos.toLSP.getStart
      )
    }
  }

  private def rangeFromString[T](
      filename: String,
      original: String,
      root: AbsolutePath,
      replaceWith: String = ""
  )(
      fn: (String, TextDocumentIdentifier, l.Range) => T
  ): Future[T] = {
    val startOffset = original.indexOf("<<")
    val endOffset = original.indexOf(">>")
    if (startOffset < 0) sys.error(s"missing <<\n$original")
    if (endOffset < 0) sys.error(s"missing >>\n$original")
    if (startOffset > endOffset)
      sys.error(s"invalid range, >> must come after <<\n$original")
    val text =
      original
        .replace("<<", replaceWith)
        .replace(">>", replaceWith)
    val input = m.Input.String(text)
    val path = root.resolve(filename)
    path.touch()
    val pos = m.Position.Range(input, startOffset, endOffset - "<<>>".length())
    for {
      _ <- didChange(filename)(_ => text)
    } yield {
      fn(
        text,
        path.toTextDocumentIdentifier,
        pos.toLSP
      )
    }
  }

  private def offsetParams(
      filename: String,
      original: String,
      root: AbsolutePath
  ): Future[(String, TextDocumentPositionParams)] =
    positionFromString(filename, original, root) {
      case (text, textId, start) =>
        (text, new TextDocumentPositionParams(textId, start))
    }

  private def codeActionParams(
      filename: String,
      original: String,
      root: AbsolutePath,
      context: CodeActionContext
  ): Future[(String, CodeActionParams)] =
    rangeFromString(filename, original, root) {
      case (text, textId, range) =>
        (text, new CodeActionParams(textId, range, context))
    }

  private def onTypeParams(
      filename: String,
      original: String,
      root: AbsolutePath,
      autoIndent: String,
      triggerChar: String
  ): Future[(String, DocumentOnTypeFormattingParams)] = {
    positionFromString(
      filename,
      original,
      root,
      replaceWith =
        if (triggerChar == "\n") triggerChar + autoIndent else triggerChar
    ) {
      case (text, textId, start) =>
        if (triggerChar == "\n") {
          start.setLine(start.getLine() + 1) // + newline
          start.setCharacter(autoIndent.size)
        }
        val params = new DocumentOnTypeFormattingParams(
          textId,
          new FormattingOptions,
          start,
          triggerChar
        )
        (text, params)
    }
  }

  private def rangeFormattingParams(
      filename: String,
      original: String,
      paste: String,
      root: AbsolutePath
  ): Future[(String, DocumentRangeFormattingParams)] = {
    positionFromString(filename, original, root, replaceWith = paste) {
      case (text, textId, start) =>
        val lines = paste.count(_ == '\n')
        val char = paste.reverse.takeWhile(_ != '\n').size
        val end = new l.Position(start.getLine() + lines, char)
        val range = new l.Range(start, end)
        val params = new DocumentRangeFormattingParams()
        params.setRange(range)
        params.setTextDocument(textId)
        (text, params)
    }
  }

  private def rangeFormattingParams(
      filename: String,
      original: String,
      root: AbsolutePath
  ): Future[(String, DocumentRangeFormattingParams)] = {
    rangeFromString(filename, original, root) {
      case (text, textId, rangeSelection) =>
        val params = new DocumentRangeFormattingParams()
        params.setRange(rangeSelection)
        params.setTextDocument(textId)
        (text, params)
    }
  }

  def assertHover(
      filename: String,
      query: String,
      expected: String,
      root: AbsolutePath = workspace
  )(implicit loc: munit.Location): Future[Unit] = {
    for {
      hover <- hover(filename, query, root)
    } yield {
      Assertions.assertNoDiff(hover, expected)
    }
  }

  def assertCodeAction(
      filename: String,
      query: String,
      expected: String,
      root: AbsolutePath = workspace
  )(implicit loc: munit.Location): Future[List[l.CodeAction]] =
    for {
      (codeActions, codeActionString) <- codeAction(filename, query, root)
    } yield {
      Assertions.assertNoDiff(codeActionString, expected)
      codeActions
    }

  def hover(
      filename: String,
      query: String,
      root: AbsolutePath
  ): Future[String] = {
    for {
      (text, params) <- offsetParams(filename, query, root)
      hover <- server.hover(params).asScala
    } yield TestHovers.renderAsString(text, Option(hover), includeRange = false)
  }

  def completion(filename: String, query: String): Future[String] = {
    completionList(filename, query).map { c =>
      formatCompletion(c, includeDetail = true)
    }
  }

  def codeAction(
      filename: String,
      query: String,
      root: AbsolutePath
  ): Future[(List[l.CodeAction], String)] =
    for {
      (_, params) <- codeActionParams(
        filename,
        query,
        root,
        new CodeActionContext(
          client.diagnostics.getOrElse(toPath(filename), Nil).asJava
        )
      )
      codeActions <- server.codeAction(params).asScala
    } yield (
      codeActions.asScala.toList,
      codeActions.map(_.getTitle()).asScala.mkString("\n")
    )

  def assertHighlight(
      filename: String,
      query: String,
      expected: String,
      root: AbsolutePath = workspace
  )(implicit loc: munit.Location): Future[Unit] = {
    for {
      highlight <- highlight(filename, query, root)
    } yield {
      Assertions.assertNoDiff(highlight, expected)
    }
  }

  def highlight(
      filename: String,
      query: String,
      root: AbsolutePath
  ): Future[String] = {
    for {
      (text, params) <- offsetParams(filename, query, root)
      highlights <- server.documentHighlights(params).asScala
    } yield {
      TestRanges.renderHighlightsAsString(text, highlights.asScala.toList)
    }
  }

  def assertRename(
      filename: String,
      query: String,
      expected: Map[String, String],
      files: Set[String],
      newName: String
  )(implicit loc: munit.Location): Future[Unit] = {
    for {
      renames <- rename(filename, query, files, newName)
    } yield {
      renames.foreach {
        case (file, obtained) =>
          assert(
            expected.contains(file),
            s"Unexpected file obtained from renames: $file"
          )
          val expectedImpl = expected(file)
          Assertions.assertNoDiff(obtained, expectedImpl)
      }
    }
  }

  def rename(
      filename: String,
      query: String,
      files: Set[String],
      newName: String
  ): Future[Map[String, String]] = {
    for {
      (_, params) <- offsetParams(filename, query, workspace)
      prepare <- server.prepareRename(params).asScala
      renameParams = new RenameParams
      _ = renameParams.setNewName(newName)
      _ = renameParams.setPosition(params.getPosition())
      _ = renameParams.setTextDocument(params.getTextDocument())
      renames <-
        if (prepare != null) {
          server.rename(renameParams).asScala
        } else {
          Future.successful(new WorkspaceEdit)
        }
      // save current file to simulate user saving in the editor
      _ <- didSave(filename)(identity)
    } yield {
      files.map { file =>
        val path = workspace.resolve(file)
        if (!buffers.contains(path)) {
          file -> path.readText
        } else {
          val code = buffers.get(path).get
          if (renames.getDocumentChanges() == null) {
            file -> code
          } else {
            val renamed = renameFile(file, renames)
            renamed -> TestRanges
              .renderEditAsString(file, code, renames)
              .getOrElse(code)
          }
        }
      }.toMap
    }
  }

  private def renameFile(file: String, renames: WorkspaceEdit) = {
    renames
      .getDocumentChanges()
      .asScala
      .collectFirst {
        case either if either.isRight() =>
          val rename = either.getRight().asInstanceOf[RenameFile]
          if (rename.getOldUri().contains(file)) {
            rename
              .getNewUri()
              .toAbsolutePath
              .toRelative(workspace)
              .toString
              .replace('\\', '/')
          } else {
            file
          }
      }
      .getOrElse(file)
  }

  def assertImplementation(
      filename: String,
      query: String,
      expected: Map[String, String],
      base: Map[String, String]
  )(implicit loc: munit.Location): Future[Unit] = {
    for {
      implementations <- implementation(filename, query, base)
    } yield {
      implementations.foreach {
        case (file, obtained) =>
          val expectedImpl = expected(file)
          Assertions.assertNoDiff(
            obtained,
            expectedImpl
          )
      }
    }
  }

  def implementation(
      filename: String,
      query: String,
      base: Map[String, String]
  ): Future[Map[String, String]] = {
    for {
      (_, params) <- offsetParams(filename, query, workspace)
      implementations <- server.implementation(params).asScala
    } yield {
      TestRanges.renderLocationsAsString(base, implementations.asScala.toList)
    }
  }

  def getReferenceLocations(
      filename: String,
      query: String,
      base: Map[String, String]
  ): Future[List[Location]] = {
    for {
      (_, params) <- offsetParams(filename, query, workspace)
      refParams = new ReferenceParams(
        params.getTextDocument(),
        params.getPosition(),
        new ReferenceContext(true)
      )
      referenceLocations <- server.references(refParams).asScala
    } yield {
      referenceLocations.asScala.toList
    }
  }

  def references(
      filename: String,
      substring: String
  ): Future[String] = {
    val path = toPath(filename)
    val input = path.toInputFromBuffers(buffers)
    val index = input.text.lastIndexOf(substring)
    if (index < 0) {
      throw new IllegalArgumentException(
        s"the string '$substring' is not a substring of text '${input.text}'"
      )
    }
    val offset = index + substring.length - 1
    val pos = m.Position.Range(input, offset, offset + 1)
    val params = new ReferenceParams(
      path.toTextDocumentIdentifier,
      new l.Position(pos.startLine, pos.startColumn),
      new ReferenceContext(true)
    )
    server.references(params).asScala.map { r =>
      r.asScala
        .map { l =>
          val path = l.getUri.toAbsolutePath
          val input = path
            .toInputFromBuffers(buffers)
            .copy(path = path.toRelative(workspace).toURI(false).toString)
          val pos = l.getRange.toMeta(input)
          pos.formatMessage("info", "reference")
        }
        .mkString("\n")
    }
  }

  def formatting(filename: String): Future[Unit] = {
    val path = toPath(filename)
    server
      .formatting(
        new DocumentFormattingParams(
          new TextDocumentIdentifier(path.toURI.toString),
          new FormattingOptions
        )
      )
      .asScala
      .map(textEdits => applyTextEdits(path, textEdits))
  }

  private def applyTextEdits(
      path: AbsolutePath,
      textEdits: util.List[TextEdit]
  ): Unit = {
    for {
      buffer <- buffers.get(path)
    } yield {
      val input = Input.String(buffer)
      val newBuffer = textEdits.asScala.foldLeft(buffer) {
        case (buf, edit) =>
          val startPosition = edit.getRange.getStart
          val endPosition = edit.getRange.getEnd
          val startOffset =
            input.toOffset(startPosition.getLine, startPosition.getCharacter)
          val endOffset =
            input.toOffset(endPosition.getLine, endPosition.getCharacter)
          buf.patch(startOffset, edit.getNewText, endOffset)
      }
      buffers.put(path, newBuffer)
    }
  }

  private def toSemanticdbTextDocument(path: AbsolutePath): s.TextDocument = {
    val input = path.toInputFromBuffers(buffers)
    val identifier = path.toTextDocumentIdentifier
    val occurrences = ListBuffer.empty[s.SymbolOccurrence]
    var last = List[String]()
    Trees.defaultDialect(input).tokenize.get.foreach { token =>
      val params = token.toPositionParams(identifier)
      val definition = server
        .definitionOrReferences(params, definitionOnly = true)
        .asJava
        .get()
      definition.definition.foreach { path =>
        if (path.isDependencySource(workspace)) {
          readonlySources(path.toNIO.getFileName.toString) = path
        }
      }
      val locations = definition.locations.asScala.toList
      val symbols = locations.map { location =>
        val isSameFile = identifier.getUri == location.getUri
        if (isSameFile) {
          s"L${location.getRange.getStart.getLine}"
        } else {
          val path = location.getUri.toAbsolutePath
          val filename = path.toNIO.getFileName
          if (path.isDependencySource(workspace)) filename.toString
          else s"$filename:${location.getRange.getStart.getLine}"
        }
      }
      last = symbols
      val occurrence = if (token.isIdentifier) {
        if (definition.symbol.isPackage) None // ignore packages
        else if (symbols.isEmpty) Some("<no symbol>")
        else Some(Symbols.Multi(symbols.sorted))
      } else {
        if (symbols.isEmpty) None // OK, expected
        else if (last == symbols) None //OK, expected
        else Some(s"unexpected: ${Symbols.Multi(symbols.sorted)}")
      }
      occurrences ++= occurrence.map { symbol =>
        s.SymbolOccurrence(Some(token.pos.toSemanticdb), symbol)
      }
    }
    s.TextDocument(
      schema = s.Schema.SEMANTICDB4,
      uri = input.path,
      text = input.text,
      occurrences = occurrences
    )
  }

  val Docstring: Regex = " *\\/?\\*.*".r
  def workspaceDefinitions: String = {
    buffers.open.toSeq
      .sortBy(_.toURI.toString)
      .map { path =>
        val textDocument = toSemanticdbTextDocument(path)
        val relpath =
          path.toRelative(workspace).toURI(isDirectory = false).toString
        val printedTextDocument = Semanticdbs.printTextDocument(textDocument)
        s"/$relpath\n$printedTextDocument"
      }
      .mkString("\n")
  }

  def documentSymbols(uri: String): Future[String] = {
    val path = toPath(uri)
    val input = path.toInputFromBuffers(buffers)
    val identifier = path.toTextDocumentIdentifier
    val params = new DocumentSymbolParams(identifier)
    for {
      documentSymbols <- server.documentSymbolResult(params)
    } yield {
      val symbols = documentSymbols.asScala.toSymbolInformation(uri)
      val textDocument = s.TextDocument(
        schema = s.Schema.SEMANTICDB4,
        language = s.Language.SCALA,
        text = input.text,
        occurrences = symbols.map(_.toSymbolOccurrence)
      )
      Semanticdbs.printTextDocument(textDocument)
    }
  }

  def buildTarget(displayName: String): String = {
    server.buildTargets
      .findByDisplayName(displayName)
      .map(_.getId().getUri())
      .getOrElse {
        val alternatives =
          server.buildTargets.all.map(_.displayName).mkString(" ")
        throw new NoSuchElementException(
          s"$displayName (alternatives: ${alternatives}"
        )
      }
  }

  def jar(filename: String): String = {
    server.buildTargets.allWorkspaceJars
      .find(_.filename.contains(filename))
      .map(_.toURI.toString())
      .getOrElse {
        val alternatives =
          server.buildTargets.allWorkspaceJars.map(_.filename).mkString(" ")
        throw new NoSuchElementException(
          s"$filename (alternatives: ${alternatives}"
        )
      }
  }

  def treeViewReveal(
      filename: String,
      linePattern: String,
      isIgnored: String => Boolean = _ => true
  )(implicit loc: munit.Location): String = {
    val path = toPath(filename)
    val line = path.toInput.value.linesIterator.zipWithIndex
      .collectFirst {
        case (text, line) if text.contains(linePattern) =>
          line
      }
      .getOrElse(
        sys.error(s"$path: not found pattern '$linePattern'")
      )
    val reveal =
      server.treeView.reveal(toPath(filename), new l.Position(line, 0)).get
    val parents = (reveal.uriChain :+ null).map { uri =>
      server.treeView.children(TreeViewChildrenParams(reveal.viewId, uri))
    }
    val label = parents.iterator
      .flatMap { r =>
        r.nodes.iterator.map { n =>
          val icon = Option(n.icon) match {
            case None => ""
            case Some(value) => s" $value"
          }
          val label = n.label + icon
          n.nodeUri -> label
        }
      }
      .toMap
      .updated("root", "root")
    val tree = parents
      .zip(reveal.uriChain :+ "root")
      .foldLeft(PrettyPrintTree.empty) {
        case (child, (parent, uri)) =>
          PrettyPrintTree(
            label(uri),
            parent.nodes
              .map(n => PrettyPrintTree(label(n.nodeUri)))
              .filterNot(t => isIgnored(t.value))
              .toList :+ child
          )
      }
    tree.toString()
  }

  def assertTreeViewChildren(
      uri: String,
      expected: String
  )(implicit loc: munit.Location): Unit = {
    val viewId: String = TreeViewProvider.Project
    val result =
      server.treeView.children(TreeViewChildrenParams(viewId, uri)).nodes
    val obtained = result
      .map { node =>
        val collapse =
          if (node.isExpanded) " +"
          else if (node.isCollapsed) " -"
          else ""
        val icon = Option(node.icon) match {
          case None => ""
          case Some(i) => " " + i
        }
        val libraryName =
          node.label.replaceAll("-(\\d+\\.)+.*\\.jar", ".jar")
        s"${libraryName}${icon}${collapse}"
      }
      .mkString("\n")
    Assertions.assertNoDiff(obtained, expected)
  }

  def textContents(filename: String): String =
    toPath(filename).toInputFromBuffers(buffers).text
  def textContentsOnDisk(filename: String): String =
    toPath(filename).toInput.text
  def bufferContents(filename: String): String =
    buffers
      .get(toPath(filename))
      .getOrElse(throw new NoSuchElementException(filename))

  def cleanUnmanagedFiles(): Unit = {
    Files.walkFileTree(
      workspace.toNIO,
      new SimpleFileVisitor[Path] {
        override def visitFile(
            file: Path,
            attrs: BasicFileAttributes
        ): FileVisitResult = {
          PathIO.extension(file) match {
            case "json" if file.getParent.endsWith(".bloop") =>
            case _ =>
              Files.delete(file)
          }
          super.visitFile(file, attrs)
        }
        override def postVisitDirectory(
            dir: Path,
            exc: IOException
        ): FileVisitResult = {
          val ls = Files.list(dir)
          val isEmpty =
            try !ls.iterator().hasNext
            finally ls.close()
          if (isEmpty) {
            Files.delete(dir)
          }
          super.postVisitDirectory(dir, exc)
        }
        override def preVisitDirectory(
            dir: Path,
            attrs: BasicFileAttributes
        ): FileVisitResult = {
          if (dir.endsWith(".metals"))
            FileVisitResult.SKIP_SUBTREE
          else super.preVisitDirectory(dir, attrs)
        }
      }
    )
  }
}

object TestingServer {
  def toPath(workspace: AbsolutePath, filename: String): AbsolutePath = {
    val path = RelativePath(filename)
    List(
      workspace,
      workspace.resolve(Directories.readonly)
    ).map(_.resolve(path))
      .find(p => Files.exists(p.toNIO))
      .getOrElse {
        throw new IllegalArgumentException(s"no such file: $filename")
      }
  }
}
