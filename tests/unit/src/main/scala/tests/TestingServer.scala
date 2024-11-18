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
import java.{util => ju}

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Failure
import scala.util.Success
import scala.util.matching.Regex
import scala.{meta => m}

import scala.meta.Input
import scala.meta.internal.implementation.Supermethods.formatMethodSymbolForQuickPick
import scala.meta.internal.io.FileIO
import scala.meta.internal.io.PathIO
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.ClasspathSearch
import scala.meta.internal.metals.ClientCommands
import scala.meta.internal.metals.Command
import scala.meta.internal.metals.Debug
import scala.meta.internal.metals.DebugSession
import scala.meta.internal.metals.DebugUnresolvedMainClassParams
import scala.meta.internal.metals.DecoderResponse
import scala.meta.internal.metals.DidFocusResult
import scala.meta.internal.metals.Directories
import scala.meta.internal.metals.ExcludedPackagesHandler
import scala.meta.internal.metals.HoverExtParams
import scala.meta.internal.metals.InitializationOptions
import scala.meta.internal.metals.ListParametrizedCommand
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsLspService
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.MetalsServerInputs
import scala.meta.internal.metals.MtagsResolver
import scala.meta.internal.metals.ParametrizedCommand
import scala.meta.internal.metals.PositionSyntax._
import scala.meta.internal.metals.ProgressTicks
import scala.meta.internal.metals.ReportContext
import scala.meta.internal.metals.ScalaVersionSelector
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.StdReportContext
import scala.meta.internal.metals.TextEdits
import scala.meta.internal.metals.Time
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.WindowStateDidChangeParams
import scala.meta.internal.metals.debug.Stoppage
import scala.meta.internal.metals.debug.TestDebugger
import scala.meta.internal.metals.findfiles._
import scala.meta.internal.metals.testProvider.BuildTargetUpdate
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.parsing.Trees
import scala.meta.internal.semanticdb.Scala.Symbols
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.tvp.TreeViewChildrenParams
import scala.meta.internal.tvp.TreeViewNodeCollapseDidChangeParams
import scala.meta.internal.tvp.TreeViewProvider
import scala.meta.internal.tvp.TreeViewVisibilityDidChangeParams
import scala.meta.internal.{semanticdb => s}
import scala.meta.io.AbsolutePath
import scala.meta.io.RelativePath

import ch.epfl.scala.{bsp4j => b}
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import munit.Tag
import org.eclipse.lsp4j.CallHierarchyIncomingCall
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams
import org.eclipse.lsp4j.CallHierarchyItem
import org.eclipse.lsp4j.CallHierarchyOutgoingCall
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams
import org.eclipse.lsp4j.CallHierarchyPrepareParams
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
import org.eclipse.lsp4j.DocumentSymbolCapabilities
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
final case class TestingServer(
    workspace: AbsolutePath,
    val client: TestingClient,
    buffers: Buffers,
    config: MetalsServerConfig,
    initialUserConfig: UserConfiguration,
    bspGlobalDirectories: List[AbsolutePath],
    sh: ScheduledExecutorService,
    time: Time,
    initializationOptions: InitializationOptions,
    mtagsResolver: MtagsResolver,
    onStartCompilation: () => Unit = () => (),
)(implicit ex: ExecutionContextExecutorService) {
  import scala.meta.internal.metals.JsonParser._

  val languageServer = new scala.meta.metals.MetalsLanguageServer(
    ex,
    sh = sh,
    TestingServer.testServerInputs(
      buffers = buffers,
      initialServerConfig = config,
      initialUserConfig = initialUserConfig,
      bspGlobalDirectories = bspGlobalDirectories,
      time = time,
      mtagsResolver = mtagsResolver,
      onStartCompilation = onStartCompilation,
    ),
  )
  languageServer.connectToLanguageClient(client)

  lazy val fullServer = languageServer.getOldMetalsLanguageServer
  def server: MetalsLspService =
    if (fullServer.folderServices.isEmpty) fullServer.fallbackService
    else headServer

  def headServer: m.internal.metals.ProjectMetalsLspService =
    fullServer.folderServices.head

  implicit val reports: ReportContext =
    new StdReportContext(workspace.toNIO, _ => None)

  private lazy val trees = new Trees(
    buffers,
    new ScalaVersionSelector(
      () => initialUserConfig,
      server.buildTargets,
    ),
  )

  private val virtualDocSources = TrieMap.empty[String, AbsolutePath]
  def statusBarHistory: String = {
    // collect both published items in the client and pending items from the server.
    val all = List(
      fullServer.statusBar.pendingItems,
      client.statusParams.asScala.map(_.text),
    ).flatten
    all.distinct.mkString("\n")
  }

  def workspaceSymbol(
      query: String,
      includeKind: Boolean = false,
      includeFilename: Boolean = false,
  ): String = {
    val infos = fullServer.workspaceSymbol(query)
    infos.foreach(info => {
      val path = info.getLocation().getUri().toAbsolutePath
      if (path.isJarFileSystem)
        virtualDocSources(path.toString.stripPrefix("/")) = path
    })
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
    server.bspSession match {
      case Some(session) =>
        val main = session.mainConnection
        for {
          workspaceBuildTargets <- main.workspaceBuildTargets()
          ids =
            workspaceBuildTargets.getTargets
              .map(_.getId)
              .asScala
              .filter(_.getUri().contains(s"?id=$buildTarget"))
          dependencySources <-
            main
              .buildTargetDependencySources(
                new b.DependencySourcesParams(ids.asJava)
              )
        } yield {
          dependencySources
            .getItems()
            .asScala
            .toSeq
            .flatMap(_.getSources().asScala)
        }
      case None =>
        Future.successful(Seq.empty)
    }
  }

  def assertGotoSuperMethod(
      asserts: Map[Int, Option[Int]],
      context: Map[Int, (l.Position, String)],
  )(implicit loc: munit.Location): Future[Unit] = {
    def exec(
        toCheck: List[(Int, Option[Int])]
    ): Future[List[Option[(l.Position, String)]]] = {
      toCheck match {
        case (pos, expectedPos) :: tl =>
          val (position, document) = context(pos)
          val command = new TextDocumentPositionParams(
            new TextDocumentIdentifier(document),
            position,
          )
          executeCommand(ServerCommands.GotoSuperMethod, command)
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
      val gotoExecutedCommandPositions = client.clientCommands.asScala.collect {
        case ClientCommands.GotoLocation(location) =>
          (location.range.getStart, location.uri)
      }
      if (initializationOptions.isVirtualDocumentSupported.exists(identity)) {

        def shortenJarPath(longPath: String): String = {
          val revSplitPath = longPath.reverse.split("!")
          if (revSplitPath.length == 2) {
            val path = revSplitPath(0).reverse
            val jarPath =
              revSplitPath(1).replace("\\", "/").takeWhile(_ != '/').reverse
            s"$jarPath$path"
          } else longPath
        }
        def shortenReadOnlyPath(longPath: String): String = {
          val path = longPath.toAbsolutePath.toRelativeInside(
            workspace.resolve(Directories.dependencies)
          )
          path.map(_.toString).getOrElse(longPath).replace("\\", "/")
        }
        val shortenedObtained = gotoExecutedCommandPositions.map {
          case (position, location) => (position, shortenJarPath(location))
        }
        val shortenedExpected = expectedGotoPositions.map {
          case (position, location) => (position, shortenReadOnlyPath(location))
        }
        Assertions.assertEquals(
          shortenedObtained,
          shortenedExpected,
        )
      } else
        Assertions.assertEquals(
          gotoExecutedCommandPositions,
          expectedGotoPositions,
        )
    }
  }

  def executeDecodeFileCommand(
      uri: String
  ): Future[DecoderResponse] = {
    executeCommand(ServerCommands.DecodeFile, uri)
      .asInstanceOf[Future[DecoderResponse]]
  }

  def assertSuperMethodHierarchy(
      uri: String,
      expectations: List[(Int, List[String])],
      context: Map[Int, l.Position],
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
          val command = new TextDocumentPositionParams(
            new TextDocumentIdentifier(uri),
            context(pos),
          )
          executeCommand(ServerCommands.SuperMethodHierarchy, command)
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
      base: Map[String, String],
  )(implicit loc: munit.Location): Future[Unit] = {
    for {
      referenceLocations <- getReferenceLocations(filename, query)
    } yield {
      Assertions.assertSimpleLocationOrdering(referenceLocations)
      val references =
        TestRanges.renderLocationsAsString(base, referenceLocations)
      references.foreach { case (file, obtained) =>
        val expectedImpl = expected(file)
        Assertions.assertNoDiff(
          obtained,
          expectedImpl,
        )
      }
    }
  }

  def assertReferenceDefinitionBijection()(implicit
      loc: munit.Location
  ): Future[Unit] = workspaceReferences().map { compare =>
    assert(compare.definition.nonEmpty, "Definitions should not be empty")
    assert(compare.references.nonEmpty, "References should not be empty")
    Assertions.assertNoDiff(
      compare.referencesFormat,
      compare.definitionFormat,
    )
  }

  def assertReferenceDefinitionDiff(
      expectedDiff: String
  )(implicit loc: munit.Location): Future[Unit] =
    workspaceReferences().map(refs =>
      Assertions.assertNoDiff(refs.diff, expectedDiff)
    )
  def workspaceReferences(): Future[WorkspaceSymbolReferences] = {
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
        },
      )
    }
    def newRef(symbol: String, loc: Location): SymbolReference = {
      val pos = loc.getRange
        .toMeta(readInput(loc.getUri))
        .getOrElse(
          throw new RuntimeException(
            s"${loc.getRange()} not contained in ${loc.getUri()}"
          )
        )
      SymbolReference(symbol, loc, pos)
    }
    for {
      source <- workspaceSources()
      input = source.toInputFromBuffers(buffers)
      identifier = source.toTextDocumentIdentifier
      token <- trees.tokenized(input).get
      if token.isIdentifier
      params = token.toPositionParams(identifier)
      definition = server
        .definitionResult(params)
        .asJava
        .get()
      if !definition.symbol.isPackage
      if !definition.definition.exists(_.isDependencySource(workspace))
      location <- definition.locations.asScala
    } {
      val buf = inverse.getOrElseUpdate(
        newRef(definition.symbol, location),
        mutable.ListBuffer.empty,
      )
      buf += new Location(source.toURI.toString, token.pos.toLsp)
    }
    val definition = Seq.newBuilder[SymbolReference]
    val references = Seq.newBuilder[SymbolReference]
    val resultFuture: Future[Unit] =
      Future
        .sequence(
          for {
            (ref, expectedLocations) <- inverse.toSeq.sortBy(_._1.symbol)
          } yield {
            val params = new ReferenceParams(
              new TextDocumentIdentifier(
                ref.location.getUri
              ),
              ref.location.getRange.getStart,
              new ReferenceContext(true),
            )
            server.referencesResult(params).map { obtainedLocations =>
              references ++= obtainedLocations.flatMap { result =>
                result.locations.map { l =>
                  newRef(result.symbol, l)
                }
              }
              definition ++= expectedLocations.map(l => newRef(ref.symbol, l))
            }
          }
        )
        .ignoreValue
    resultFuture.map(_ =>
      WorkspaceSymbolReferences(
        references.result().distinct,
        definition.result().distinct,
      )
    )
  }

  def assertCallHierarchy[C](
      expected: Map[String, String],
      base: Map[String, String],
      specifiedUri: Option[String],
      calls: List[C],
      getItem: C => CallHierarchyItem,
      getFromRanges: C => List[l.Range],
  ): (List[C], CallHierarchyItem) = {
    val pattern = """(<|>)(\?)(<|>)""".r
    val itemExpected = expected.map { case (filename, code) =>
      filename -> pattern.replaceAllIn(code, "")
    }

    val (call, remaining) = calls.partition(call => {
      val item = getItem(call)
      TestRanges
        .renderLocationsAsString(
          base,
          List(new Location(item.getUri(), item.getSelectionRange())),
        )
        .forall { case (file, obtained) =>
          itemExpected(file) == obtained
        }
    })

    assert(
      call.nonEmpty,
      s"Expected item \"\"\"$itemExpected\"\"\" was not found.",
    )

    val fromRangesExpected = expected.map { case (filename, code) =>
      filename -> """(<|>)(\?)(<|>)""".r.replaceAllIn(
        code.replaceAll("(<<|>>)", ""),
        m => m.group(1) + m.group(3),
      )
    }

    val item = call.headOption
      .map(call => {
        val item = getItem(call)
        val uri = specifiedUri.getOrElse(item.getUri())
        TestRanges
          .renderLocationsAsString(
            base,
            getFromRanges(call).map(range => new Location(uri, range)),
          )
          .foreach { case (file, obtained) =>
            val expectedImpl = fromRangesExpected(file)
            Assertions.assertNoDiff(
              obtained,
              expectedImpl,
            )
          }
        item
      })
      .getOrElse {
        throw new IllegalArgumentException(
          "An `<<...>>>` that specifies caller postion is not found"
        )
      }

    (remaining, item)
  }

  def initialize(
      workspaceFolders: Option[List[String]] = None
  ): Future[l.InitializeResult] = {
    val params = new InitializeParams
    val workspaceCapabilities = new WorkspaceClientCapabilities()
    workspaceCapabilities.setInlayHint(
      new l.InlayHintWorkspaceCapabilities(true)
    )
    val textDocumentCapabilities = new TextDocumentClientCapabilities
    val windowCapabilities = new l.WindowClientCapabilities()
    windowCapabilities.setWorkDoneProgress(true)
    textDocumentCapabilities.setFoldingRange(new FoldingRangeCapabilities)
    val completionItemCapabilities = new l.CompletionItemCapabilities(true)
    textDocumentCapabilities.setCompletion(
      new l.CompletionCapabilities(completionItemCapabilities)
    )
    val documentSymbolCapabilities = new DocumentSymbolCapabilities()
    documentSymbolCapabilities.setHierarchicalDocumentSymbolSupport(true)
    textDocumentCapabilities.setDocumentSymbol(documentSymbolCapabilities)

    // Yes, this is a bit gross :/
    // However, I want to only get the existing fields that are being set
    // much like it'd be when a client actually sends this. This will just
    // collect the fields that are set, get the values, and then make them into
    // a map that will become a JsonObject to pass in as the InitializationOptions
    val existingInitOptions =
      initializationOptions.getClass.getDeclaredFields
        .map { field =>
          field.setAccessible(true)
          field.getName -> field.get(initializationOptions)
        }
        .collect {
          case (key, Some(value: Boolean)) => key -> value
          case (key, Some(value)) => key -> value.toString
        }
        .toMap
        .asJava

    params.setInitializationOptions(existingInitOptions.toJson)
    params.setCapabilities(
      new ClientCapabilities(
        workspaceCapabilities,
        textDocumentCapabilities,
        windowCapabilities,
        Map.empty.asJava.toJson,
      )
    )

    workspaceFolders match {
      case Some(workspaceFolders) =>
        params.setWorkspaceFolders(
          workspaceFolders
            .map(file => new WorkspaceFolder(toPath(file).toURI.toString, file))
            .asJava
        )
      case None =>
        params.setRootUri(workspace.toURI.toString)
    }

    languageServer.initialize(params).asScala
  }

  def initialized(): Future[Unit] = {
    languageServer.initialized(new InitializedParams).asScala
  }

  def assertBuildServerConnection(): Unit = {
    fullServer.folderServices.foreach { service =>
      require(
        service.bspSession.isDefined,
        s"Build server ${service.path} did not initialize",
      )
    }
  }

  def toPath(filename: String): AbsolutePath = {
    TestingServer.toPath(workspace, filename, virtualDocSources)
  }

  def toPathFromSymbol(symbol: String, filename: String): AbsolutePath = {
    workspaceSymbol(symbol)
    TestingServer.toPath(workspace, filename, virtualDocSources)
  }

  def executeCommand[T](
      command: ParametrizedCommand[T],
      param: T,
  ): Future[Any] = {
    Debug.printEnclosing()
    scribe.info(s"Executing command [${command.id}]")
    fullServer.executeCommand(command.toExecuteCommandParams(param)).asScala
  }

  def executeCommand[T](
      command: ListParametrizedCommand[T],
      param: T*
  ): Future[Any] = {
    Debug.printEnclosing()
    scribe.info(s"Executing command [${command.id}]")
    fullServer.executeCommand(command.toExecuteCommandParams(param: _*)).asScala
  }

  def executeCommand[T](command: Command): Future[Any] = {
    Debug.printEnclosing()
    scribe.info(s"Executing command [${command.id}]")
    fullServer.executeCommand(command.toExecuteCommandParams()).asScala
  }

  def listBuildTargets: Future[List[String]] = {
    for {
      targetsArray <- executeCommand(ServerCommands.ListBuildTargets)
    } yield targetsArray.toJson.as[Array[String]] match {
      case Failure(exception) =>
        scribe.error("Could not read build targets", exception)
        Nil
      case Success(targets) =>
        targets.toList
    }
  }

  /**
   * Operating on strings can be dangerous, but needed for running unknown commands
   * and for the StartDebugAdapter command, which doesn't have a stable argument.
   */
  def executeCommandUnsafe(
      command: String,
      params: Seq[Object],
  ): Future[Any] = {
    Debug.printEnclosing()
    scribe.info(s"Executing command [$command]")
    val args: java.util.List[Object] =
      params.map(_.toJson.asInstanceOf[Object]).asJava
    fullServer.executeCommand(new ExecuteCommandParams(command, args)).asScala
  }

  def waitFor(millis: Long): Future[Unit] = Future { Thread.sleep(millis) }

  def startDebugging(
      target: String,
      kind: String,
      parameter: AnyRef,
      stoppageHandler: Stoppage.Handler = Stoppage.Handler.Continue,
      requestOtherThreadStackTrace: Boolean = false,
  ): Future[TestDebugger] = {

    assertSystemExit(parameter)
    val targets = List(new b.BuildTargetIdentifier(buildTarget(target)))
    val params =
      new b.DebugSessionParams(targets.asJava)
    params.setDataKind(kind)
    params.setData(parameter.toJson)
    executeCommandUnsafe(ServerCommands.StartDebugAdapter.id, Seq(params))
      .collect { case DebugSession(_, uri) =>
        scribe.info(s"Starting debug session for $uri")
        TestDebugger(
          URI.create(uri),
          stoppageHandler,
          requestOtherThreadStackTrace,
        )
      }
  }

  // note(@tgodzik) all test should have `System.exit(0)` added to avoid occasional issue due to:
  // https://stackoverflow.com/questions/2225737/error-jdwp-unable-to-get-jni-1-2-environment
  private def assertSystemExit(parameter: AnyRef) = {
    def check() = try {
      val nonTarget = workspace.list.filter(_.filename != "target")
      val workspaceFiles =
        nonTarget.flatMap(_.listRecursive.filter(_.isScalaOrJava).toList)
      val usesSystemExit =
        workspaceFiles.exists(_.text.contains("System.exit(0)"))
      if (!usesSystemExit)
        throw new RuntimeException(
          "All debug test for main classes should have `System.exit(0)`"
        )
    } catch {
      case _: IOException =>
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
      params: AnyRef,
      stoppageHandler: Stoppage.Handler = Stoppage.Handler.Continue,
  ): Future[TestDebugger] = {
    assertSystemExit(params)
    executeCommandUnsafe(ServerCommands.StartDebugAdapter.id, Seq(params))
      .collect { case DebugSession(_, uri) =>
        TestDebugger(URI.create(uri), stoppageHandler)
      }
  }

  def didFocus(filename: String): Future[DidFocusResult.Value] = {
    fullServer.didFocus(toPath(filename).toURI.toString).asScala
  }

  def windowStateDidChange(focused: Boolean): Unit = {
    fullServer.windowStateDidChange(WindowStateDidChangeParams(focused))
  }

  def didSave(filename: String)(fn: String => String): Future[Unit] = {
    Debug.printEnclosing(filename)
    val abspath = toPath(filename)
    val oldText = abspath.toInputFromBuffers(buffers).text
    val newText = fn(oldText)
    Files.write(
      abspath.toNIO,
      newText.getBytes(StandardCharsets.UTF_8),
    )
    fullServer
      .didSave(
        new DidSaveTextDocumentParams(
          new TextDocumentIdentifier(abspath.toURI.toString)
        )
      )
      .asScala
  }

  def didChange(filename: String)(fn: String => String): Future[Unit] = {
    val abspath = toPath(filename)
    val oldText = abspath.toInputFromBuffers(buffers).text
    val newText = fn(oldText)
    didChange(filename, newText)
  }

  def didChange(filename: String, newText: String): Future[Unit] = {
    Debug.printEnclosing(filename)
    val abspath = toPath(filename)
    fullServer
      .didChange(
        new DidChangeTextDocumentParams(
          new VersionedTextDocumentIdentifier(abspath.toURI.toString, 0),
          Collections.singletonList(new TextDocumentContentChangeEvent(newText)),
        )
      )
      .asScala
  }

  def analyzeStacktrace(stacktrace: String): Seq[l.CodeLens] = {
    server.stacktraceAnalyzer.stacktraceLenses(
      stacktrace.split('\n').toList
    )
  }

  def exportEvaluation(filename: String): Option[String] = {
    val path = toPath(filename)
    server.worksheetProvider.copyWorksheetOutput(path)
  }

  def didOpen(filename: String): Future[Unit] = {
    Debug.printEnclosing(filename)
    val abspath = toPath(filename)
    val uri = abspath.toURI.toString
    val extension = PathIO.extension(abspath.toNIO)
    val text = abspath.readText
    fullServer
      .didOpen(
        new DidOpenTextDocumentParams(
          new TextDocumentItem(uri, extension, 0, text)
        )
      )
      .asScala
  }

  def didClose(filename: String): Future[Unit] = {
    Debug.printEnclosing(filename)
    val abspath = toPath(filename)
    val uri = abspath.toURI.toString
    Future.successful {
      fullServer
        .didClose(
          new DidCloseTextDocumentParams(
            new TextDocumentIdentifier(uri)
          )
        )
    }
  }

  def shutdown(): Future[Unit] = {
    fullServer.shutdown().asScala
  }

  def didChangeConfiguration(config: String): Future[Unit] = {
    val json = UserConfiguration.parse(config)

    // lsp -didChangeConfiguration method should be called with a wrapped object
    val didChangeJson = new JsonObject()
    didChangeJson.add("metals", json)

    val params = new DidChangeConfigurationParams(didChangeJson)
    fullServer.didChangeConfiguration(params).asScala
  }

  def willRenameFiles(
      workspaceFiles: Set[String],
      fileRenames: Map[String, String],
  ): Future[Map[String, String]] = {
    val lspRenames = fileRenames.toList.map { case (oldFilename, newFilename) =>
      val oldUri = workspace.resolve(oldFilename).toURI.toString
      val newUri = workspace.resolve(newFilename).toURI.toString
      new l.FileRename(oldUri, newUri)
    }.asJava
    val params = new l.RenameFilesParams(lspRenames)
    for {
      editsOrNull <- fullServer.willRenameFiles(params).asScala
      edits = Option(editsOrNull).getOrElse(new WorkspaceEdit)
      updatedSources = workspaceFiles.map { file =>
        val path = workspace.resolve(file)
        val code = path.readText
        val updatedCode = TestRanges
          .renderEditAsString(file, code, edits)
          .getOrElse(code)
        file -> updatedCode
      }.toMap
    } yield updatedSources
  }

  def completionList(
      filename: String,
      query: String,
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
      new CompletionParams(path.toTextDocumentIdentifier, pos.toLsp.getStart)
    fullServer.completion(params).asScala
  }

  def foldingRange(filename: String): Future[String] = {
    val path = toPath(filename)
    val uri = path.toURI.toString
    val params = new FoldingRangeRequestParams(new TextDocumentIdentifier(uri))
    for {
      ranges <- fullServer.foldingRange(params).asScala
      textEdits = RangesTextEdits.fromFoldingRanges(ranges)
    } yield TextEdits.applyEdits(textContents(filename), textEdits)
  }

  def assertFolded(filename: String, expected: String)(implicit
      loc: munit.Location
  ): Future[Unit] =
    for {
      folded <- foldingRange(filename)
      _ = Assertions.assertNoDiff(folded, expected)
    } yield ()

  def retrieveRanges(
      filename: String,
      expected: String,
  ): Future[ju.List[l.SelectionRange]] = {
    val path = toPath(filename)
    val input = path.toInputFromBuffers(buffers)
    val offset = expected.indexOf("@@")
    if (offset < 0) sys.error("missing @@")
    val start = input.text.indexOf(expected.replace("@@", ""))
    val point = start + offset
    val pos = m.Position.Range(input, point, point)
    val params =
      new l.SelectionRangeParams(
        path.toTextDocumentIdentifier,
        List(pos.toLsp.getStart).asJava,
      )

    fullServer.selectionRange(params).asScala
  }

  def assertSelectionRanges(
      filename: String,
      ranges: List[l.SelectionRange],
      expected: List[String],
  ): Unit = {
    expected.headOption.foreach { expectedRange =>
      val edits = RangesTextEdits.fromSelectionRanges(ranges)
      val edited = TextEdits.applyEdits(textContents(filename), edits)
      Assertions.assertNoDiff(edited, expectedRange)
      assertSelectionRanges(filename, ranges.map(_.getParent()), expected.tail)
    }
  }

  def onTypeFormatting(
      filename: String,
      query: String,
      expected: String,
      autoIndent: String,
      triggerChar: String,
      root: AbsolutePath = workspace,
  )(implicit loc: munit.Location): Future[Unit] = {
    for {
      (_, params) <- onTypeParams(
        filename,
        query,
        root,
        autoIndent,
        triggerChar,
      )
      multiline <- fullServer.onTypeFormatting(params).asScala
      format = TextEdits.applyEdits(
        textContents(filename),
        multiline.asScala.toList,
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
      root: AbsolutePath,
      formattingOptions: Option[FormattingOptions],
  )(implicit loc: munit.Location): Future[Unit] = {
    for {
      (_, params) <- rangeFormattingParams(
        filename,
        query,
        paste,
        root,
        formattingOptions,
      )
      multiline <- fullServer.rangeFormatting(params).asScala
      format = TextEdits.applyEdits(
        textContents(filename),
        multiline.asScala.toList,
      )
    } yield {
      Assertions.assertNoDiff(format, expected)
    }
  }
  def rangeFormatting(
      filename: String,
      query: String,
      expected: String,
      root: AbsolutePath = workspace,
      formattingOptions: Option[FormattingOptions] = None,
  )(implicit loc: munit.Location): Future[Unit] = {
    for {
      (_, params) <- rangeFormattingParams(
        filename,
        query,
        root,
        formattingOptions,
      )
      multiline <- fullServer.rangeFormatting(params).asScala
      format = TextEdits.applyEdits(
        textContents(filename),
        multiline.asScala.toList,
      )
    } yield {
      Assertions.assertNoDiff(format, expected)
    }
  }

  def discoverTestSuites(
      files: List[String],
      uri: Option[String] = None,
  ): Future[List[BuildTargetUpdate]] = {
    val paths = files.map(filename => toPath(filename))
    val maxRetries = 6
    def askServer(
        retries: Int,
        backoff: Int,
    ): Future[List[BuildTargetUpdate]] = {
      val arg = ServerCommands.DiscoverTestParams(uri.orNull)
      executeCommand(ServerCommands.DiscoverTestSuites, arg)
        .asInstanceOf[Future[ju.List[BuildTargetUpdate]]]
        .map(_.asScala.toList)
        .flatMap { r =>
          if (r.exists(_.events.asScala.nonEmpty)) {
            Future.successful(r)
          } else if (retries > 0) {
            scribe.info(
              s"Fetched empty test discovery, wait for $backoff and try again"
            )
            Thread.sleep(backoff)
            askServer(retries - 1, backoff * 2)
          } else {
            val error =
              s"Could not fetch any test classes in $maxRetries tries"
            Future.failed(new NoSuchElementException(error))
          }
        }
    }

    val compilations =
      paths.map(path =>
        fullServer.getServiceFor(path).compilations.compileFile(path)
      )

    for {
      _ <- Future.sequence(compilations)
      _ <- waitFor(util.concurrent.TimeUnit.SECONDS.toMillis(1))
      classes <- askServer(maxRetries, backoff = 100)
    } yield classes
  }

  def codeLensesText(
      filename: String,
      printCommand: Boolean = false,
      minExpectedLenses: Int = 1,
  )(
      maxRetries: Int
  ): Future[String] = {
    for {
      lenses <- codeLenses(filename, maxRetries, minExpectedLenses)
      textEdits = CodeLensesTextEdits(lenses, printCommand)
    } yield TextEdits.applyEdits(textContents(filename), textEdits.toList)
  }

  def codeLenses(
      filename: String,
      maxRetries: Int = 4,
      minExpectedLenses: Int = 1,
  ): Future[List[l.CodeLens]] = {
    Debug.printEnclosing(filename)
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
    def getLenses = fullServer
      .codeLens(params)
      .asScala
      .map(_.asScala)
      .withTimeout(10, util.concurrent.TimeUnit.SECONDS)
      .recover { _ =>
        scribe.info(s"Timeout for fetching lenses reached for $filename")
        Nil
      }

    val handler = { (refreshCount: Int) =>
      scribe.info(s"Refreshing model for $filename")
      if (refreshCount > 0)
        for {
          lenses <- getLenses
        } {
          if (lenses.size >= minExpectedLenses)
            codeLenses.trySuccess(lenses.toList)
          else if (retries > 0) {
            retries -= 1
            server.compilations.compileFile(path)
          } else {
            val error = s"Could not fetch any code lenses in $maxRetries tries"
            codeLenses.tryFailure(new NoSuchElementException(error))
          }
        }
    }

    client.refreshModelHandler = handler

    for {
      // model is refreshed only for focused document
      // this will also trigger compilation
      _ <- fullServer.didFocus(uri).asScala
      lenses <- getLenses
        .flatMap { lenses =>
          if (lenses.size >= minExpectedLenses) Future.successful(lenses)
          else codeLenses.future
        }
        .withTimeout(60, util.concurrent.TimeUnit.SECONDS)
    } yield lenses.toList
  }

  def formatCompletion(
      completion: CompletionList,
      includeDetail: Boolean,
      filter: String => Boolean = _ => true,
      saveCompletionOrder: Boolean = false,
  ): String = {
    val ordering: Ordering[String] =
      if (saveCompletionOrder) (_: String, _: String) => 1 else Ordering.String

    val items = completion.getItems.asScala
      .sortBy(_.getLabel())(ordering)
      .map(item => fullServer.completionItemResolve(item).get())

    items.iterator
      .filter(item => filter(item.getLabel()))
      .map { item =>
        val label = TestCompletions.getFullyQualifiedLabel(item)
        val shouldIncludeDetail = item.getDetail != null && includeDetail
        val detail =
          if (shouldIncludeDetail && !label.contains(item.getDetail))
            " " + item.getDetail
          else ""
        label + detail
      }
      .mkString("\n")
  }

  private def positionFromString[T](
      filename: String,
      original: String,
      root: AbsolutePath,
      replaceWith: String = "",
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
        pos.toLsp.getStart,
      )
    }
  }

  private def rangeFromString[T](
      filename: String,
      original: String,
      root: AbsolutePath,
      replaceWith: String = "",
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
    val pos = m.Position.Range(input, startOffset, endOffset - "<<".length())
    for {
      _ <- didChange(filename)(_ => text)
    } yield {
      fn(
        text,
        path.toTextDocumentIdentifier,
        pos.toLsp,
      )
    }
  }

  def offsetParams(
      filename: String,
      original: String,
      root: AbsolutePath,
  ): Future[(String, TextDocumentPositionParams)] =
    positionFromString(filename, original, root) { case (text, textId, start) =>
      (text, new TextDocumentPositionParams(textId, start))
    }

  private def hoverExtParams(
      filename: String,
      original: String,
      root: AbsolutePath,
  ): Future[(String, HoverExtParams)] =
    positionFromString(filename, original, root) { case (text, textId, start) =>
      (text, new HoverExtParams(textId, start))
    }

  private def codeActionParams(
      filename: String,
      original: String,
      root: AbsolutePath,
      context: CodeActionContext,
  ): Future[(String, CodeActionParams)] =
    rangeFromString(filename, original, root) { case (text, textId, range) =>
      (text, new CodeActionParams(textId, range, context))
    }

  private def onTypeParams(
      filename: String,
      original: String,
      root: AbsolutePath,
      autoIndent: String,
      triggerChar: String,
  ): Future[(String, DocumentOnTypeFormattingParams)] = {
    positionFromString(
      filename,
      original,
      root,
      replaceWith =
        if (triggerChar == "\n") triggerChar + autoIndent else triggerChar,
    ) { case (text, textId, start) =>
      if (triggerChar == "\n") {
        start.setLine(start.getLine() + 1) // + newline
        start.setCharacter(autoIndent.size)
      }
      val params = new DocumentOnTypeFormattingParams(
        textId,
        new FormattingOptions,
        start,
        triggerChar,
      )
      (text, params)
    }
  }

  private def rangeFormattingParams(
      filename: String,
      original: String,
      paste: String,
      root: AbsolutePath,
      formattingOptions: Option[FormattingOptions],
  ): Future[(String, DocumentRangeFormattingParams)] = {
    positionFromString(filename, original, root, replaceWith = paste) {
      case (text, textId, start) =>
        val lines = paste.count(_ == '\n')
        val char =
          if (lines == 0) start.getCharacter() + paste.size
          else paste.reverse.takeWhile(_ != '\n').size
        val end = new l.Position(start.getLine() + lines, char)
        val range = new l.Range(start, end)
        val params = new DocumentRangeFormattingParams()
        params.setRange(range)
        params.setTextDocument(textId)
        formattingOptions.foreach(params.setOptions)
        (text, params)
    }
  }

  private def rangeFormattingParams(
      filename: String,
      original: String,
      root: AbsolutePath,
      formattingOptions: Option[FormattingOptions],
  ): Future[(String, DocumentRangeFormattingParams)] = {
    rangeFromString(filename, original, root) {
      case (text, textId, rangeSelection) =>
        val params = new DocumentRangeFormattingParams()
        params.setRange(rangeSelection)
        params.setTextDocument(textId)
        formattingOptions.foreach(params.setOptions)
        (text, params)
    }
  }

  def assertHoverAtLine(
      filename: String,
      query: String,
      expected: String,
      root: AbsolutePath = workspace,
  )(implicit loc: munit.Location): Future[Unit] = {
    val text = root.resolve(filename).readText
    val fullQuery = text.replace(query.replace("@@", "") + "\n", query + "\n")
    assertHover(filename, fullQuery, expected, root)
  }

  def assertHover(
      filename: String,
      query: String,
      expected: String,
      root: AbsolutePath = workspace,
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
      kind: List[String],
      root: AbsolutePath = workspace,
      filterAction: l.CodeAction => Boolean = _ => true,
  )(implicit loc: munit.Location): Future[List[l.CodeAction]] =
    for {
      (codeActions, codeActionString) <- codeAction(
        filename,
        query,
        root,
        kind,
        filterAction,
      )
    } yield {
      Assertions.assertNoDiff(codeActionString, expected)
      codeActions
    }

  def hover(
      filename: String,
      query: String,
      root: AbsolutePath,
  ): Future[String] = {
    for {
      (text, params) <- hoverExtParams(filename, query, root)
      hover <- fullServer.hover(params).asScala
    } yield TestHovers.renderAsString(text, Option(hover), includeRange = false)
  }

  def completion(filename: String, query: String): Future[String] = {
    completionList(filename, query).map { c =>
      formatCompletion(c, includeDetail = true)
    }
  }

  def completionItemResolve(
      item: l.CompletionItem
  ): Future[l.CompletionItem] = {
    fullServer.completionItemResolve(item).asScala
  }

  def codeAction(
      filename: String,
      query: String,
      root: AbsolutePath,
      kind: List[String],
      filterAction: l.CodeAction => Boolean,
  ): Future[(List[l.CodeAction], String)] =
    for {
      (_, params) <- codeActionParams(
        filename,
        query,
        root,
        new CodeActionContext(
          client.diagnostics.getOrElse(toPath(filename), Nil).asJava,
          if (kind.nonEmpty) kind.asJava else null,
        ),
      )
      codeActions <- fullServer
        .codeAction(params)
        .asScala
        .map(_.asScala.filter(filterAction))
    } yield (
      codeActions.toList.filter(_.getDisabled() == null),
      codeActions
        .map(a =>
          a.getTitle() +
            Option(a.getDisabled()).fold("")(_ => " (disabled)")
        )
        .mkString("\n"),
    )

  def assertSemanticHighlight(
      filePath: String,
      expected: String,
      fileContent: String,
  )(implicit location: munit.Location): Future[Unit] = {
    val uri = toPath(filePath).toTextDocumentIdentifier
    val params = new org.eclipse.lsp4j.SemanticTokensParams(uri)

    for {
      obtainedTokens <- fullServer.semanticTokensFull(params).asScala
    } yield {
      val obtained =
        if (obtainedTokens != null)
          TestSemanticTokens.semanticString(
            fileContent,
            obtainedTokens.getData().map(_.toInt).asScala.toList,
          )
        else expected

      Assertions.assertNoDiff(
        obtained,
        expected,
      )
    }
  }

  def assertInlayHints(
      filename: String,
      expected: String,
      root: AbsolutePath = workspace,
  )(implicit
      location: munit.Location
  ): Future[Unit] = {
    val fileContent = TestInlayHints.removeInlayHints(expected)
    assertInlayHints(filename, fileContent, expected, root)
  }

  def assertInlayHints(
      filename: String,
      fileContent: String,
      expected: String,
      root: AbsolutePath,
  )(implicit
      location: munit.Location
  ): Future[Unit] = {
    for {
      hints <- inlayHints(filename, fileContent, root)
    } yield {
      Assertions.assertNoDiff(
        TestInlayHints.applyInlayHints(fileContent, hints),
        expected,
      )
    }
  }

  def inlayHints(
      filename: String,
      fileContent: String,
      root: AbsolutePath = workspace,
  ): Future[List[l.InlayHint]] = {
    val path = root.resolve(filename)
    val input = m.Input.String(fileContent)
    path.touch()
    val pos = m.Position.Range(input, 0, fileContent.length)
    val uri = path.toTextDocumentIdentifier
    val range = pos.toLsp
    val params = new org.eclipse.lsp4j.InlayHintParams(uri, range)
    for {
      _ <- didSave(filename)(_ => fileContent)
      inlayHints <- fullServer.inlayHints(params).asScala
    } yield inlayHints.asScala.toList
  }

  def assertHighlight(
      filename: String,
      query: String,
      expected: String,
      root: AbsolutePath = workspace,
  )(implicit loc: munit.Location): Future[Unit] = {
    for {
      highlight <- highlight(filename, query, root)
    } yield {
      Assertions.assertNoDiff(highlight, expected)
    }
  }

  def definition(
      filename: String,
      query: String,
      root: AbsolutePath,
  ): Future[List[Location]] = {
    for {
      (_, params) <- offsetParams(filename, query, root)
      definition <- fullServer.definition(params).asScala
    } yield {
      definition.asScala.toList
    }
  }

  def highlight(
      filename: String,
      query: String,
      root: AbsolutePath,
  ): Future[String] = {
    for {
      (text, params) <- offsetParams(filename, query, root)
      highlights <- fullServer.documentHighlights(params).asScala
    } yield {
      TestRanges.renderHighlightsAsString(text, highlights.asScala.toList)
    }
  }

  def assertRename(
      filename: String,
      query: String,
      expected: Map[String, String],
      files: Set[String],
      newName: String,
  )(implicit loc: munit.Location): Future[Unit] = {
    for {
      renames <- rename(filename, query, files, newName)
    } yield {
      renames.foreach { case (file, obtained) =>
        assert(
          expected.contains(file),
          s"Unexpected file obtained from renames: $file",
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
      newName: String,
  ): Future[Map[String, String]] = {
    for {
      (_, params) <- offsetParams(filename, query, workspace)
      prepare <- fullServer.prepareRename(params).asScala
      renameParams = new RenameParams
      _ = renameParams.setNewName(newName)
      _ = renameParams.setPosition(params.getPosition())
      _ = renameParams.setTextDocument(params.getTextDocument())
      renames <-
        if (prepare != null) {
          fullServer.rename(renameParams).asScala
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
      base: Map[String, String],
  )(implicit loc: munit.Location): Future[Unit] = {
    for {
      implementations <- implementation(filename, query, base)
    } yield {
      implementations.foreach { case (file, obtained) =>
        val expectedImpl = expected(file)
        Assertions.assertNoDiff(
          obtained,
          expectedImpl,
        )
      }
    }
  }

  def implementation(
      filename: String,
      query: String,
      base: Map[String, String],
  ): Future[Map[String, String]] = {
    Debug.printEnclosing()
    implementation(filename, query).map(
      TestRanges.renderLocationsAsString(base, _)
    )
  }

  def implementation(
      filename: String,
      query: String,
  ): Future[List[Location]] = {
    for {
      (_, params) <- offsetParams(filename, query, workspace)
      implementations <- fullServer.implementation(params).asScala
    } yield implementations.asScala.toList
  }

  def getReferenceLocations(
      filename: String,
      query: String,
  ): Future[List[Location]] = {
    for {
      (_, params) <- offsetParams(filename, query, workspace)
      refParams = new ReferenceParams(
        params.getTextDocument(),
        params.getPosition(),
        new ReferenceContext(true),
      )
      referenceLocations <- fullServer.references(refParams).asScala
    } yield {
      referenceLocations.asScala.toList
    }
  }

  def references(
      filename: String,
      substring: String,
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
      new ReferenceContext(true),
    )
    fullServer.references(params).asScala.map { r =>
      r.asScala
        .sortBy { l =>
          val start = l.getRange().getStart()
          (start.getLine(), start.getCharacter())
        }
        .map { l =>
          val path = l.getUri.toAbsolutePath
          val shortPath =
            if (path.isJarFileSystem) path.toString.replace("\\", "/")
            else path.toRelative(workspace).toURI(false).toString
          val input = path
            .toInputFromBuffers(buffers)
            .copy(path = shortPath)
          val pos = l.getRange
            .toMeta(input)
            .getOrElse(
              throw new RuntimeException(
                s"Cannot find ${l.getRange()} in ${l.getUri()}"
              )
            )
          pos.formatMessage("info", "reference")
        }
        .mkString("\n")
    }
  }

  def prepareCallHierarchy(
      filename: String,
      query: String,
  ): Future[Option[CallHierarchyItem]] = {
    for {
      (_, params) <- offsetParams(filename, query, workspace)
      prepareParams = new CallHierarchyPrepareParams(
        params.getTextDocument(),
        params.getPosition(),
      )
      result <- fullServer.prepareCallHierarchy(prepareParams).asScala
    } yield {
      result.asScala.headOption
    }
  }

  def incomingCalls(
      item: CallHierarchyItem
  ): Future[List[CallHierarchyIncomingCall]] = {
    item.setData(item.getData.toJsonObject)
    for {
      result <- fullServer
        .callHierarchyIncomingCalls(new CallHierarchyIncomingCallsParams(item))
        .asScala
    } yield {
      result.asScala.toList
    }
  }

  def outgoingCalls(
      item: CallHierarchyItem
  ): Future[List[CallHierarchyOutgoingCall]] = {
    item.setData(item.getData.toJsonObject)
    for {
      result <- fullServer
        .callHierarchyOutgoingCalls(new CallHierarchyOutgoingCallsParams(item))
        .asScala
    } yield {
      result.asScala.toList
    }
  }

  def formatting(filename: String): Future[Unit] = {
    val path = toPath(filename)
    fullServer
      .formatting(
        new DocumentFormattingParams(
          new TextDocumentIdentifier(path.toURI.toString),
          new FormattingOptions,
        )
      )
      .asScala
      .map(textEdits => applyTextEdits(path, textEdits))
  }

  private def applyTextEdits(
      path: AbsolutePath,
      textEdits: util.List[TextEdit],
  ): Unit = {
    for {
      buffer <- buffers.get(path)
    } yield {
      val input = Input.String(buffer)
      val newBuffer = textEdits.asScala.foldLeft(buffer) { case (buf, edit) =>
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
    trees.tokenized(input).get.foreach { token =>
      val params = token.toPositionParams(identifier)
      // Scala 3 doesn't count ` as part of the word which is the same as most editors
      if (token.text.startsWith("`")) {
        val position = params.getPosition()
        position.setCharacter(position.getCharacter() + 1)
      }
      val definition = server
        .definitionOrReferences(params, definitionOnly = true)
        .asJava
        .get()
      definition.definition.foreach { path =>
        if (path.isJarFileSystem) {
          virtualDocSources(path.toString.stripPrefix("/")) = path
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
        else if (last == symbols) None // OK, expected
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
      occurrences = occurrences.toSeq,
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
      documentSymbols <- fullServer.documentSymbol(params).asScala
    } yield {
      val symbols =
        documentSymbols.getLeft.asScala.toSeq.toSymbolInformation(uri)
      val textDocument = s.TextDocument(
        schema = s.Schema.SEMANTICDB4,
        language = s.Language.SCALA,
        text = input.text,
        occurrences = symbols.map(_.toSymbolOccurrence),
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
          server.buildTargets.all
            .map(_.getDisplayName())
            .mkString(" ")
        throw new NoSuchElementException(
          s"$displayName (alternatives: ${alternatives}"
        )
      }
  }

  def jar(filename: String): String = {
    server.buildTargets.allSourceJars
      .find(_.filename.contains(filename))
      .map(_.toURI.toString())
      .getOrElse {
        val alternatives =
          server.buildTargets.allSourceJars
            .map(_.filename)
            .mkString(" ")
        throw new NoSuchElementException(
          s"$filename (alternatives: ${alternatives}"
        )
      }
  }

  def treeViewReveal(
      filename: String,
      linePattern: String,
      isIgnored: String => Boolean = _ => true,
  ): String = {
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
      fullServer.treeView
        .reveal(toPath(filename), new l.Position(line, 0))
        .getOrElse {
          sys.error(s"$path: cannot reveal")
        }
    val parents = (reveal.uriChain :+ null).map { uri =>
      fullServer.treeView.children(
        TreeViewChildrenParams(reveal.viewId, uri)
      )
    }

    val labelsMap = parents.iterator
      .flatMap { r =>
        r.nodes.iterator.map { n =>
          val icon = Option(n.icon) match {
            case None => ""
            case Some(value) => s" $value"
          }
          val label = n.label + icon
          n.nodeUri.toLowerCase -> label
        }
      }
      .toMap
      .updated("root", "root")

    def label(uri: String, default: String): String =
      labelsMap.get(uri) match {
        case None =>
          scribe.warn(s"Cannot find label for $uri")
          scribe.warn(labelsMap.mkString("\n"))
          default
        case Some(value) => value
      }
    val tree = parents
      .zip(reveal.uriChain :+ "root")
      .foldLeft(PrettyPrintTree.empty) { case (child, (parent, uri)) =>
        val realUri =
          if (uri.contains("-sources.jar")) uri
          else uri.replace(".jar", "-sources.jar")
        PrettyPrintTree(
          label(realUri.toLowerCase, realUri),
          parent.nodes
            .map(n => PrettyPrintTree(label(n.nodeUri.toLowerCase, n.nodeUri)))
            .filterNot(t => isIgnored(t.value))
            .toList :+ child,
        )
      }
    tree.toString()
  }

  def assertTreeViewChildren(
      uri: String,
      expected: String,
  )(implicit loc: munit.Location): Unit = {
    val viewId: String = TreeViewProvider.Project
    val result =
      fullServer.treeView
        .children(TreeViewChildrenParams(viewId, uri))
        .nodes
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

  def treeViewVisibilityDidChange(
      viewId: String,
      isVisible: Boolean,
  ): Future[Unit] = {
    fullServer
      .treeViewVisibilityDidChange(
        TreeViewVisibilityDidChangeParams(viewId, isVisible)
      )
      .asScala
  }

  def treeViewNodeCollapseDidChange(
      viewId: String,
      nodeId: String,
      isCollapsed: Boolean,
  ): Future[Unit] = {
    fullServer
      .treeViewNodeCollapseDidChange(
        TreeViewNodeCollapseDidChangeParams(viewId, nodeId, isCollapsed)
      )
      .asScala
  }

  def findTextInDependencyJars(
      include: String,
      pattern: String,
  ): Future[List[Location]] = {
    fullServer
      .findTextInDependencyJars(
        FindTextInDependencyJarsRequest(
          FindTextInFilesOptions(include = include, exclude = null),
          TextSearchQuery(
            pattern = pattern,
            isRegExp = null,
            isCaseSensitive = null,
            isWordMatch = null,
          ),
        )
      )
      .asScala
      .map(_.asScala.toList)
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
            attrs: BasicFileAttributes,
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
            exc: IOException,
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
            attrs: BasicFileAttributes,
        ): FileVisitResult = {
          if (dir.endsWith(".metals"))
            FileVisitResult.SKIP_SUBTREE
          else super.preVisitDirectory(dir, attrs)
        }
      },
    )
  }
}

object TestingServer {
  def toPath(
      workspace: AbsolutePath,
      filename: String,
      virtualDocSources: TrieMap[String, AbsolutePath],
  ): AbsolutePath = {
    val path = RelativePath(filename)
    val base = List(workspace, workspace.resolve(Directories.readonly))
    val dependencies = workspace.resolve(Directories.dependencies).list.toList
    val all = base ++ dependencies
    all
      .map(_.resolve(path))
      .find(p => Files.exists(p.toNIO))
      .orElse(virtualDocSources.get(filename))
      .getOrElse {
        throw new IllegalArgumentException(s"no such file: $filename")
      }
  }

  val virtualDocTag = new Tag("UseVirtualDocs")

  val TestDefault: InitializationOptions =
    InitializationOptions.Default.copy(
      debuggingProvider = Some(true),
      runProvider = Some(true),
      treeViewProvider = Some(true),
    )

  // Caching is done using a key: dependency jars + excludedPackages setting + bucket size
  // This allows to avoid indexing classpath per every test and saves ~4 min on CI.
  // Test with a unique dependencies creates a new index while in most cases (zero deps + default excludedPackages setting)
  // they reuse the default one.
  val testingClasspathSearchIndexer: ClasspathSearch.Indexer =
    new ClasspathSearch.Indexer {

      private val cache: mutable.Map[
        (collection.Seq[Path], ExcludedPackagesHandler, Int),
        ClasspathSearch,
      ] =
        mutable.Map.empty

      override def index(
          classpath: collection.Seq[Path],
          excludedPackage: ExcludedPackagesHandler,
          bucketSize: Int,
      ): ClasspathSearch = {
        val key = (classpath, excludedPackage, bucketSize)
        cache.get(key) match {
          case None =>
            val v = ClasspathSearch.Indexer.default.index(
              classpath,
              excludedPackage,
              bucketSize,
            )
            cache.update(key, v)
            v
          case Some(v) => v
        }
      }

    }

  def testServerInputs(
      buffers: Buffers,
      time: Time,
      initialServerConfig: MetalsServerConfig,
      initialUserConfig: UserConfiguration,
      bspGlobalDirectories: List[AbsolutePath],
      mtagsResolver: MtagsResolver,
      onStartCompilation: () => Unit,
  ): MetalsServerInputs = MetalsServerInputs(
    buffers,
    time,
    initialServerConfig,
    initialUserConfig,
    bspGlobalDirectories,
    mtagsResolver,
    onStartCompilation,
    redirectSystemOut = false,
    progressTicks = ProgressTicks.none,
    isReliableFileWatcher = System.getenv("CI") != "true",
    classpathSearchIndexer = TestingServer.testingClasspathSearchIndexer,
    charset = StandardCharsets.UTF_8,
  )

}
