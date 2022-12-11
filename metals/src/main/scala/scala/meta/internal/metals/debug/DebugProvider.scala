package scala.meta.internal.metals.debug

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.Collections.singletonList
import java.util.concurrent.TimeUnit
import java.{util => ju}

import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.control.NonFatal

import scala.meta.internal.metals.BuildServerConnection
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.Cancelable
import scala.meta.internal.metals.ClientCommands
import scala.meta.internal.metals.ClientConfiguration
import scala.meta.internal.metals.Compilations
import scala.meta.internal.metals.Compilers
import scala.meta.internal.metals.DebugDiscoveryParams
import scala.meta.internal.metals.DebugUnresolvedAttachRemoteParams
import scala.meta.internal.metals.DebugUnresolvedMainClassParams
import scala.meta.internal.metals.DebugUnresolvedTestClassParams
import scala.meta.internal.metals.JsonParser
import scala.meta.internal.metals.JsonParser._
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.Messages.UnresolvedDebugSessionParams
import scala.meta.internal.metals.MetalsBuildClient
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MutableCancelable
import scala.meta.internal.metals.ScalaTestSuites
import scala.meta.internal.metals.ScalaTestSuitesDebugRequest
import scala.meta.internal.metals.SourceMapper
import scala.meta.internal.metals.StacktraceAnalyzer
import scala.meta.internal.metals.StatusBar
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.clients.language.MetalsQuickPickItem
import scala.meta.internal.metals.clients.language.MetalsQuickPickParams
import scala.meta.internal.metals.clients.language.MetalsStatusParams
import scala.meta.internal.metals.config.RunType
import scala.meta.internal.metals.config.RunType._
import scala.meta.internal.mtags.DefinitionAlternatives.GlobalSymbol
import scala.meta.internal.mtags.OnDemandSymbolIndex
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.mtags.Symbol
import scala.meta.internal.semanticdb.Scala.Descriptor
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.DebugSessionParams
import ch.epfl.scala.bsp4j.ScalaMainClass
import ch.epfl.scala.{bsp4j => b}
import com.google.common.net.InetAddresses
import com.google.gson.JsonElement
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType

/**
 * @param supportsTestSelection test selection hasn't been defined in BSP spec yet.
 * Currently it is supported only by bloop.
 */
class DebugProvider(
    workspace: AbsolutePath,
    buildTargets: BuildTargets,
    buildTargetClasses: BuildTargetClasses,
    compilations: Compilations,
    languageClient: MetalsLanguageClient,
    buildClient: MetalsBuildClient,
    index: OnDemandSymbolIndex,
    stacktraceAnalyzer: StacktraceAnalyzer,
    clientConfig: ClientConfiguration,
    semanticdbs: Semanticdbs,
    compilers: Compilers,
    statusBar: StatusBar,
    sourceMapper: SourceMapper,
) extends Cancelable {

  import DebugProvider._

  private val debugSessions = new MutableCancelable()

  override def cancel(): Unit = debugSessions.cancel()

  lazy val buildTargetClassesFinder = new BuildTargetClassesFinder(
    buildTargets,
    buildTargetClasses,
    index,
  )

  def start(
      parameters: b.DebugSessionParams
  )(implicit ec: ExecutionContext): Future[DebugServer] = {
    for {
      sessionName <- Future.fromTry(parseSessionName(parameters))
      jvmOptionsTranslatedParams = translateJvmParams(parameters)
      buildServer <- buildServerConnect(parameters)
        .fold[Future[BuildServerConnection]](BuildServerUnavailableError)(
          Future.successful
        )
      debugServer <- start(sessionName, jvmOptionsTranslatedParams, buildServer)
    } yield debugServer
  }

  private def start(
      sessionName: String,
      parameters: b.DebugSessionParams,
      buildServer: BuildServerConnection,
  )(implicit ec: ExecutionContext): Future[DebugServer] = {
    val inetAddress = InetAddress.getByName("127.0.0.1")
    val proxyServer = new ServerSocket(0, 50, inetAddress)
    val host = InetAddresses.toUriString(proxyServer.getInetAddress)
    val port = proxyServer.getLocalPort
    proxyServer.setSoTimeout(10 * 1000)
    val uri = URI.create(s"tcp://$host:$port")
    val connectedToServer = Promise[Unit]()

    val awaitClient =
      () => Future(proxyServer.accept())

    // long timeout, since server might take a while to compile the project
    val connectToServer = () => {
      val targets = parameters.getTargets().asScala.toSeq

      compilations.compilationFinished(targets).flatMap { _ =>
        buildServer
          .startDebugSession(parameters)
          .withTimeout(60, TimeUnit.SECONDS)
          .map { uri =>
            val socket = connect(uri)
            connectedToServer.trySuccess(())
            socket
          }
          .recover { case exception =>
            connectedToServer.tryFailure(exception)
            throw exception
          }
      }
    }

    val proxyFactory = { () =>
      val targets = parameters.getTargets.asScala.toSeq
        .map(_.getUri)
        .map(new BuildTargetIdentifier(_))

      val debugAdapter =
        if (buildServer.usesScalaDebugAdapter2x) {
          MetalsDebugAdapter(
            buildTargets,
            targets,
            supportVirtualDocuments = clientConfig.isVirtualDocumentSupported(),
          )
        } else {
          throw new IllegalArgumentException(
            s"${buildServer.name} ${buildServer.version} does not support scala-debug-adapter 2.x"
          )
        }
      DebugProxy.open(
        sessionName,
        awaitClient,
        connectToServer,
        debugAdapter,
        stacktraceAnalyzer,
        compilers,
        workspace,
        clientConfig.disableColorOutput(),
        statusBar,
        sourceMapper,
      )
    }
    val server = new DebugServer(sessionName, uri, proxyFactory)

    debugSessions.add(server)
    server.listen.andThen { case _ =>
      proxyServer.close()
      debugSessions.remove(server)
    }

    connectedToServer.future.map(_ => server)
  }

  /**
   * Given a BuildTargetIdentifier either get the displayName of that build
   * target or default to the full URI to display to the user.
   */
  private def displayName(buildTargetIdentifier: BuildTargetIdentifier) =
    buildTargets
      .info(buildTargetIdentifier)
      .map(_.getDisplayName)
      .getOrElse(buildTargetIdentifier.getUri)

  private def requestMain(
      mainClasses: List[ScalaMainClass]
  )(implicit ec: ExecutionContext): Future[ScalaMainClass] = {
    languageClient
      .metalsQuickPick(
        new MetalsQuickPickParams(
          mainClasses.map { m =>
            val cls = m.getClassName()
            new MetalsQuickPickItem(cls, cls)
          }.asJava,
          placeHolder = Messages.MainClass.message,
        )
      )
      .asScala
      .collect { case Some(choice) =>
        mainClasses.find { clazz =>
          clazz.getClassName() == choice.itemId
        }
      }
      .collect { case Some(main) => main }
  }

  private def buildServerConnect(parameters: b.DebugSessionParams) = for {
    targetId <- parameters.getTargets().asScala.headOption
    buildServer <- buildTargets.buildServerOf(targetId)
  } yield buildServer

  private def supportsTestSelection(targetId: b.BuildTargetIdentifier) = {
    buildTargets.buildServerOf(targetId).exists(_.supportsTestSelection)
  }

  private def envFromFile(
      envFile: Option[String]
  )(implicit ec: ExecutionContext): Future[List[String]] =
    envFile
      .map { file =>
        val path = AbsolutePath(file)(workspace)
        DotEnvFileParser
          .parse(path)
          .map(_.map { case (key, value) => s"$key=$value" }.toList)
      }
      .getOrElse(Future.successful(List.empty))

  private def createMainParams(
      main: ScalaMainClass,
      target: BuildTargetIdentifier,
      args: Option[ju.List[String]],
      jvmOptions: Option[ju.List[String]],
      env: List[String],
      envFile: Option[String],
  )(implicit ec: ExecutionContext) = {
    main.setArguments(args.getOrElse(ju.Collections.emptyList()))
    main.setJvmOptions(
      jvmOptions.getOrElse(ju.Collections.emptyList())
    )
    envFromFile(envFile).map { envFromFile =>
      main.setEnvironmentVariables((envFromFile ::: env).asJava)
      new b.DebugSessionParams(
        singletonList(target),
        b.DebugSessionParamsDataKind.SCALA_MAIN_CLASS,
        main.toJson,
      )
    }
  }

  private def createEnvList(env: ju.Map[String, String]) = {
    env.asScala.map { case (key, value) =>
      s"$key=$value"
    }.toList
  }

  private def verifyMain(
      buildTarget: BuildTargetIdentifier,
      classes: List[ScalaMainClass],
      params: DebugDiscoveryParams,
  )(implicit ec: ExecutionContext): Future[DebugSessionParams] = {
    val env = Option(params.env).toList.flatMap(createEnvList)

    classes match {
      case Nil =>
        Future.failed(
          BuildTargetContainsNoMainException(displayName(buildTarget))
        )
      case main :: Nil =>
        createMainParams(
          main,
          buildTarget,
          Option(params.args),
          Option(params.jvmOptions),
          env,
          Option(params.envFile),
        )
      case multiple =>
        requestMain(multiple).flatMap { main =>
          createMainParams(
            main,
            buildTarget,
            Option(params.args),
            Option(params.jvmOptions),
            env,
            Option(params.envFile),
          )
        }
    }
  }

  private def resolveInFile(
      buildTarget: BuildTargetIdentifier,
      classes: TrieMap[
        BuildTargetClasses.Symbol,
        ScalaMainClass,
      ],
      testClasses: TrieMap[
        BuildTargetClasses.Symbol,
        BuildTargetClasses.TestSymbolInfo,
      ],
      params: DebugDiscoveryParams,
  )(implicit ec: ExecutionContext) = {
    val path = params.path.toAbsolutePath
    semanticdbs
      .textDocument(path)
      .documentIncludingStale
      .fold[Future[DebugSessionParams]] {
        Future.failed(SemanticDbNotFoundException)
      } { textDocument =>
        lazy val tests = for {
          symbolInfo <- textDocument.symbols
          symbol = symbolInfo.symbol
          testSymbolInfo <- testClasses.get(symbol)
        } yield testSymbolInfo.fullyQualifiedName
        val mains = for {
          occurrence <- textDocument.occurrences
          if occurrence.role.isDefinition || occurrence.symbol == "scala/main#"
          symbol = occurrence.symbol
          mainClass <- {
            val normal = classes.get(symbol)
            val fromAnnot = DebugProvider
              .mainFromAnnotation(occurrence, textDocument)
              .flatMap(classes.get(_))
            List(normal, fromAnnot).flatten
          }
        } yield mainClass
        if (mains.nonEmpty) {
          verifyMain(buildTarget, mains.toList, params)
        } else if (tests.nonEmpty) {
          Future(
            new b.DebugSessionParams(
              singletonList(buildTarget),
              b.DebugSessionParamsDataKind.SCALA_TEST_SUITES,
              tests.asJava.toJson,
            )
          )
        } else {
          Future.failed(NoRunOptionException)
        }
      }
  }

  /**
   * When given the already formed params (most likely from a code lens) make
   * sure the workspace doesn't have any errors which would cause the debug
   * session to not actually work, but fail silently.
   */
  def ensureNoWorkspaceErrors(
      buildTargets: Seq[BuildTargetIdentifier]
  )(implicit ec: ExecutionContext): Future[Unit] = {
    val hasErrors = buildTargets.exists { target =>
      buildClient.buildHasErrors(target)
    }
    val result =
      if (hasErrors) Future.failed(WorkspaceErrorsException)
      else Future.unit

    result.failed.foreach(reportErrors)
    result
  }

  /**
   * Given fully unresolved params this figures out the runType that was passed
   * in and then discovers either the main methods for the build target the
   * path belongs to or finds the tests for the current file or build target
   */
  def debugDiscovery(
      params: DebugDiscoveryParams
  )(implicit ec: ExecutionContext): Future[b.DebugSessionParams] = {
    val runTypeO = RunType.fromString(params.runType)
    val path = params.path.toAbsolutePath
    val buildTargetO = buildTargets.inverseSources(path)

    lazy val mainClasses = (bti: BuildTargetIdentifier) =>
      buildTargetClasses.classesOf(bti).mainClasses

    lazy val testClasses = (bti: BuildTargetIdentifier) =>
      buildTargetClasses.classesOf(bti).testClasses

    val result: Future[DebugSessionParams] = (runTypeO, buildTargetO) match {
      case (_, Some(buildTarget)) if buildClient.buildHasErrors(buildTarget) =>
        Future.failed(WorkspaceErrorsException)
      case (_, None) =>
        Future.failed(BuildTargetNotFoundForPathException(path))
      case (None, _) =>
        Future.failed(RunType.UnknownRunTypeException(params.runType))
      case (Some(Run), Some(target)) =>
        verifyMain(target, mainClasses(target).values.toList, params)
      case (Some(RunOrTestFile), Some(target)) =>
        resolveInFile(target, mainClasses(target), testClasses(target), params)
      case (Some(TestFile), Some(target)) if testClasses(target).isEmpty =>
        Future.failed(
          NoTestsFoundException("file", path.toString())
        )
      case (Some(TestTarget), Some(target)) if testClasses(target).isEmpty =>
        Future.failed(
          NoTestsFoundException("build target", displayName(target))
        )
      case (Some(TestFile), Some(target)) =>
        semanticdbs
          .textDocument(path)
          .documentIncludingStale
          .fold[Future[Seq[BuildTargetClasses.FullyQualifiedClassName]]] {
            Future.failed(SemanticDbNotFoundException)
          } { textDocument =>
            Future {
              for {
                symbolInfo <- textDocument.symbols
                symbol = symbolInfo.symbol
                testSymbolInfo <- testClasses(target).get(symbol)
              } yield testSymbolInfo.fullyQualifiedName
            }
          }
          .map { tests =>
            new b.DebugSessionParams(
              singletonList(target),
              b.DebugSessionParamsDataKind.SCALA_TEST_SUITES,
              tests.asJava.toJson,
            )
          }
      case (Some(TestTarget), Some(target)) =>
        Future {
          new b.DebugSessionParams(
            singletonList(target),
            b.DebugSessionParamsDataKind.SCALA_TEST_SUITES,
            testClasses(target).values
              .map(_.fullyQualifiedName)
              .toList
              .asJava
              .toJson,
          )
        }
    }

    result.failed.foreach(reportErrors)
    result
  }

  def resolveMainClassParams(
      params: DebugUnresolvedMainClassParams
  )(implicit ec: ExecutionContext): Future[b.DebugSessionParams] = {
    val result = withRebuildRetry(() =>
      buildTargetClassesFinder
        .findMainClassAndItsBuildTarget(
          params.mainClass,
          Option(params.buildTarget),
        )
    ).flatMap {
      case (_, target) :: _ if buildClient.buildHasErrors(target.getId()) =>
        Future.failed(WorkspaceErrorsException)
      case (clazz, target) :: others =>
        if (others.nonEmpty) {
          reportOtherBuildTargets(
            clazz.getClassName(),
            target,
            others,
            "main",
          )
        }

        val env = Option(params.env).toList.flatMap(createEnvList)
        createMainParams(
          clazz,
          target.getId(),
          Option(params.args),
          Option(params.jvmOptions),
          env,
          Option(params.envFile),
        )

      // should not really happen due to
      // `findMainClassAndItsBuildTarget` succeeding with non-empty list
      case Nil => Future.failed(new ju.NoSuchElementException(params.mainClass))
    }
    result.failed.foreach(reportErrors)
    result
  }

  def resolveTestClassParams(
      params: DebugUnresolvedTestClassParams
  )(implicit ec: ExecutionContext): Future[b.DebugSessionParams] = {
    val result = withRebuildRetry(() => {
      buildTargetClassesFinder
        .findTestClassAndItsBuildTarget(
          params.testClass,
          Option(params.buildTarget),
        )
    }).flatMap {
      case (_, target) :: _ if buildClient.buildHasErrors(target.getId()) =>
        Future.failed(WorkspaceErrorsException)
      case (clazz, target) :: others =>
        if (others.nonEmpty) {
          reportOtherBuildTargets(
            clazz,
            target,
            others,
            "test",
          )
        }
        val env = Option(params.env).toList.flatMap(createEnvList)

        envFromFile(Option(params.envFile)).map { envFromFile =>
          val scalaTestSuite = new b.ScalaTestSuites(
            List(
              new b.ScalaTestSuiteSelection(params.testClass, Nil.asJava)
            ).asJava,
            Option(params.jvmOptions).getOrElse(Nil.asJava),
            (envFromFile ::: env).asJava,
          )
          new b.DebugSessionParams(
            singletonList(target.getId()),
            b.DebugSessionParamsDataKind.SCALA_TEST_SUITES_SELECTION,
            scalaTestSuite.toJson,
          )
        }
      // should not really happen due to
      // `findMainClassAndItsBuildTarget` succeeding with non-empty list
      case Nil => Future.failed(new ju.NoSuchElementException(params.testClass))
    }
    result.failed.foreach(reportErrors)
    result
  }

  def resolveAttachRemoteParams(
      params: DebugUnresolvedAttachRemoteParams
  )(implicit ec: ExecutionContext): Future[b.DebugSessionParams] = {
    val result = buildTargets.findByDisplayName(params.buildTarget) match {
      case Some(target) =>
        Future.successful(
          new b.DebugSessionParams(
            singletonList(target.getId()),
            b.DebugSessionParamsDataKind.SCALA_ATTACH_REMOTE,
            ().toJson,
          )
        )
      case None =>
        Future.failed(BuildTargetUndefinedException())
    }
    result.failed.foreach(reportErrors)
    result
  }

  /**
   * Validate if build target provided in params exists.
   * On the contrary, test classes aren't validated.
   *
   * If build tool doesn't support test selection fallback to the already
   * defined and supported SCALA_TEST_SUITES request kind.
   */
  def resolveTestSelectionParams(
      request: ScalaTestSuitesDebugRequest
  )(implicit ec: ExecutionContext): Future[b.DebugSessionParams] = {
    val makeDebugSession = () =>
      buildTargets.info(request.target) match {
        case Some(buildTarget) =>
          val debugSession =
            if (supportsTestSelection(request.target))
              new b.DebugSessionParams(
                singletonList(buildTarget.getId),
                DebugProvider.ScalaTestSelection,
                request.requestData.toJson,
              )
            else
              new b.DebugSessionParams(
                singletonList(buildTarget.getId),
                b.DebugSessionParamsDataKind.SCALA_TEST_SUITES,
                request.requestData.suites.map(_.className).toJson,
              )
          Future.successful(debugSession)
        case None =>
          val error = BuildTargetNotFoundException(request.target.getUri)
          reportErrors(error)
          Future.failed(error)
      }

    for {
      _ <- compilations.compileTarget(request.target)
      _ <- ensureNoWorkspaceErrors(List(request.target))
      result <- makeDebugSession()
    } yield result
  }

  private val reportErrors: PartialFunction[Throwable, Unit] = {
    case _ if buildClient.buildHasErrors =>
      languageClient.metalsStatus(
        Messages.DebugErrorsPresent(clientConfig.icons())
      )
    case t: ClassNotFoundException =>
      languageClient.showMessage(
        Messages.DebugClassNotFound.invalidClass(t.getMessage())
      )
    case ClassNotFoundInBuildTargetException(cls, buildTarget) =>
      languageClient.showMessage(
        Messages.DebugClassNotFound
          .invalidTargetClass(cls, buildTarget.getDisplayName())
      )
    case BuildTargetNotFoundException(target) =>
      languageClient.showMessage(
        Messages.DebugClassNotFound
          .invalidTarget(target)
      )
    case e: BuildTargetNotFoundForPathException =>
      languageClient.showMessage(
        Messages.errorMessageParams(e.getMessage())
      )
    case e: BuildTargetContainsNoMainException =>
      languageClient.showMessage(
        Messages.errorMessageParams(e.getMessage())
      )
    case e: BuildTargetUndefinedException =>
      languageClient.showMessage(
        Messages.errorMessageParams(e.getMessage())
      )
    case e: NoTestsFoundException =>
      languageClient.showMessage(
        Messages.errorMessageParams(e.getMessage())
      )
    case e: RunType.UnknownRunTypeException =>
      languageClient.showMessage(
        Messages.errorMessageParams(e.getMessage())
      )
    case e @ SemanticDbNotFoundException =>
      languageClient.metalsStatus(
        MetalsStatusParams(
          text = s"${clientConfig.icons.alert}Build misconfiguration",
          tooltip = e.getMessage(),
          command = ClientCommands.RunDoctor.id,
        )
      )
    case e @ DotEnvFileParser.InvalidEnvFileException(_) =>
      languageClient.showMessage(Messages.errorMessageParams(e.getMessage()))

    case e @ NoRunOptionException =>
      languageClient.showMessage(
        Messages.errorMessageParams(e.getMessage())
      )
  }

  private def parseSessionName(
      parameters: b.DebugSessionParams
  ): Try[String] = {
    parameters.getData match {
      case json: JsonElement =>
        parameters.getDataKind match {
          case b.DebugSessionParamsDataKind.SCALA_MAIN_CLASS =>
            json.as[b.ScalaMainClass].map(_.getClassName)
          case b.DebugSessionParamsDataKind.SCALA_TEST_SUITES =>
            json.as[ju.List[String]].map(_.asScala.sorted.mkString(";"))
          case b.DebugSessionParamsDataKind.SCALA_ATTACH_REMOTE =>
            Success("attach-remote-debug-session")
          case DebugProvider.ScalaTestSelection =>
            json.as[ScalaTestSuites].map { params =>
              params.suites.asScala
                .map(suite =>
                  s"${suite.className}(${suite.tests.asScala.mkString(", ")})"
                )
                .mkString(";")
            }
        }
      case data =>
        val dataType = data.getClass.getSimpleName
        Failure(new IllegalStateException(s"Data is $dataType. Expecting json"))
    }
  }

  private def translateJvmParams(
      parameters: b.DebugSessionParams
  ): b.DebugSessionParams = {
    parameters.getData match {
      case json: JsonElement
          if parameters.getDataKind == b.DebugSessionParamsDataKind.SCALA_MAIN_CLASS =>
        json.as[b.ScalaMainClass].foreach { main =>
          if (main.getEnvironmentVariables() == null) {
            main.setEnvironmentVariables(Nil.asJava)
          }
          parameters.setData(main.toJsonObject)
        }
        parameters
      case _ =>
        parameters
    }
  }

  private def connect(uri: URI): Socket = {
    val socket = new Socket()
    /* Using "0.0.0.0" seems to be sometimes causing issues
     * and it does not seem to be a standard across systems
     * This made tests extremely flaky.
     */
    val host = uri.getHost match {
      case "0.0.0.0" => "127.0.0.1"
      case other => other
    }
    val address = new InetSocketAddress(host, uri.getPort)
    val timeout = TimeUnit.SECONDS.toMillis(10).toInt
    try {
      socket.connect(address, timeout)
      socket
    } catch {
      case NonFatal(e) =>
        scribe.warn(
          s"Could not connect to java process via ${address.getHostName()}:${address.getPort()}"
        )
        if (uri.getHost() != host) {
          val alternateAddress =
            new InetSocketAddress(uri.getHost(), uri.getPort)
          scribe.warn(
            s"Retrying with ${alternateAddress.getHostName()}:${alternateAddress.getPort()}"
          )
          socket.connect(alternateAddress, timeout)
          socket
        } else {
          throw e
        }
    }
  }

  private def withRebuildRetry[A](
      f: () => Try[A]
  )(implicit ec: ExecutionContext): Future[A] = {
    Future.fromTry(f()).recoverWith {
      case ClassNotFoundInBuildTargetException(_, buildTarget) =>
        val target = Seq(buildTarget.getId())
        for {
          _ <- compilations.compileTargets(target)
          _ <- buildTargetClasses.rebuildIndex(target)
          result <- Future.fromTry(f())
        } yield result
      case _: ClassNotFoundException =>
        val allTargetIds = buildTargets.allBuildTargetIds
        for {
          _ <- compilations.compileTargets(allTargetIds)
          _ <- buildTargetClasses.rebuildIndex(allTargetIds)
          result <- Future.fromTry(f())
        } yield result
    }
  }

  private def reportOtherBuildTargets(
      className: String,
      buildTarget: b.BuildTarget,
      others: List[(_, b.BuildTarget)],
      mainOrTest: String,
  ) = {
    val otherTargets = others.map(_._2.getDisplayName())
    languageClient.showMessage(
      new MessageParams(
        MessageType.Warning,
        UnresolvedDebugSessionParams
          .runningClassMultipleBuildTargetsMessage(
            className,
            buildTarget.getDisplayName(),
            otherTargets,
            mainOrTest,
          ),
      )
    )
  }

  private lazy val BuildServerUnavailableError =
    Future.failed(new IllegalStateException("Build server unavailable"))

}

object DebugProvider {

  /**
   * Given an occurence and a text document return the symbol of a main method
   * that could be defined using the Scala 3 @main annotation.
   *
   * @param occurrence The symbol occurence you're checking against the document.
   * @param textDocument The document of the current file.
   * @return Possible symbol name of main.
   */
  def mainFromAnnotation(
      occurrence: SymbolOccurrence,
      textDocument: TextDocument,
  ): Option[String] = {
    if (occurrence.symbol == "scala/main#") {
      occurrence.range match {
        case Some(range) =>
          val closestOccurence = textDocument.occurrences.minBy { occ =>
            occ.range
              .filter { rng =>
                occ.symbol != "scala/main#" &&
                rng.endLine - range.endLine >= 0 &&
                rng.endCharacter - rng.startCharacter > 0
              }
              .map(rng =>
                (
                  rng.endLine - range.endLine,
                  rng.endCharacter - range.endCharacter,
                )
              )
              .getOrElse((Int.MaxValue, Int.MaxValue))
          }
          dropSourceFromToplevelSymbol(closestOccurence.symbol)

        case None => None
      }
    } else {
      None
    }

  }

  /**
   * Converts Scala3 sorceToplevelSymbol into a plain one that corresponds to class name.
   * From `3.1.0` plain names were removed from occurrences because they are synthetic.
   * Example:
   *   `foo/Foo$package.mainMethod().` -> `foo/mainMethod#`
   */
  private def dropSourceFromToplevelSymbol(symbol: String): Option[String] = {
    Symbol(symbol) match {
      case GlobalSymbol(
            GlobalSymbol(
              owner,
              Descriptor.Term(_),
            ),
            Descriptor.Method(name, _),
          ) =>
        val converted = GlobalSymbol(owner, Descriptor.Term(name))
        Some(converted.value)
      case _ =>
        None
    }
  }

  object DebugParametersJsonParsers {
    lazy val debugSessionParamsParser = new JsonParser.Of[b.DebugSessionParams]
    lazy val mainClassParamsParser =
      new JsonParser.Of[DebugUnresolvedMainClassParams]
    lazy val testSuitesParamsParser =
      new JsonParser.Of[ScalaTestSuitesDebugRequest]
    lazy val testClassParamsParser =
      new JsonParser.Of[DebugUnresolvedTestClassParams]
    lazy val attachRemoteParamsParser =
      new JsonParser.Of[DebugUnresolvedAttachRemoteParams]
    lazy val unresolvedParamsParser =
      new JsonParser.Of[DebugDiscoveryParams]
  }

  val ScalaTestSelection = "scala-test-suites-selection"

  case object WorkspaceErrorsException
      extends Exception(
        s"Cannot run class, since the workspace has errors."
      )
  case object NoRunOptionException
      extends Exception(
        s"There is nothing to run or test in the current file."
      )
  case object SemanticDbNotFoundException
      extends Exception(
        "Build misconfiguration. No semanticdb can be found for you file, please check the doctor."
      )
}
