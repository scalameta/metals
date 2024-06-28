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
import scala.meta.internal.metals.ClientConfiguration
import scala.meta.internal.metals.Compilations
import scala.meta.internal.metals.Compilers
import scala.meta.internal.metals.DebugDiscoveryParams
import scala.meta.internal.metals.DebugSession
import scala.meta.internal.metals.DebugUnresolvedMainClassParams
import scala.meta.internal.metals.DebugUnresolvedTestClassParams
import scala.meta.internal.metals.JavaBinary
import scala.meta.internal.metals.JsonParser._
import scala.meta.internal.metals.JvmOpts
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.Messages.UnresolvedDebugSessionParams
import scala.meta.internal.metals.MetalsBuildClient
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MutableCancelable
import scala.meta.internal.metals.ScalaTestSuites
import scala.meta.internal.metals.ScalaTestSuitesDebugRequest
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.SourceMapper
import scala.meta.internal.metals.StacktraceAnalyzer
import scala.meta.internal.metals.StatusBar
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.WorkDoneProgress
import scala.meta.internal.metals.clients.language.LogForwarder
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.clients.language.MetalsQuickPickItem
import scala.meta.internal.metals.clients.language.MetalsQuickPickParams
import scala.meta.internal.metals.clients.language.MetalsStatusParams
import scala.meta.internal.metals.config.RunType
import scala.meta.internal.metals.config.RunType._
import scala.meta.internal.metals.testProvider.TestSuitesProvider
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
    semanticdbs: () => Semanticdbs,
    compilers: Compilers,
    statusBar: StatusBar,
    workDoneProgress: WorkDoneProgress,
    sourceMapper: SourceMapper,
    userConfig: () => UserConfiguration,
    testProvider: TestSuitesProvider,
) extends Cancelable
    with LogForwarder {

  import DebugProvider._

  private val runningLocal = new ju.concurrent.atomic.AtomicBoolean(false)

  private val debugSessions = new MutableCancelable()

  private val currentRunner =
    new ju.concurrent.atomic.AtomicReference[DebugRunner](null)

  override def info(message: String): Unit = {
    val runner = currentRunner.get()
    if (runner != null) runner.stdout(message)
  }

  override def error(message: String): Unit = {
    val runner = currentRunner.get()
    if (runner != null) runner.error(message)
  }

  override def cancel(): Unit = {
    val runner = currentRunner.get()
    if (runner != null) runner.cancel()
    debugSessions.cancel()
  }

  lazy val buildTargetClassesFinder = new BuildTargetClassesFinder(
    buildTargets,
    buildTargetClasses,
    index,
  )

  def start(
      parameters: b.DebugSessionParams
  )(implicit ec: ExecutionContext): Future[DebugServer] = {
    val cancelPromise = Promise[Unit]()
    for {
      sessionName <- Future.fromTry(parseSessionName(parameters))
      jvmOptionsTranslatedParams = translateJvmParams(parameters)
      buildServer <- buildServerConnect(parameters)
        .fold[Future[BuildServerConnection]](BuildServerUnavailableError)(
          Future.successful
        )
      isJvm = parameters
        .getTargets()
        .asScala
        .flatMap(buildTargets.scalaTarget)
        .forall(
          _.scalaInfo.getPlatform == b.ScalaPlatform.JVM
        )
      debugServer <-
        if (isJvm)
          workDoneProgress.trackFuture(
            "Starting debug server",
            start(
              sessionName,
              jvmOptionsTranslatedParams,
              buildServer,
              cancelPromise,
            ),
            Some(() => cancelPromise.trySuccess(())),
          )
        else
          runLocally(
            sessionName,
            jvmOptionsTranslatedParams,
            buildServer,
            cancelPromise,
          )
    } yield debugServer
  }

  private def runLocally(
      sessionName: String,
      parameters: b.DebugSessionParams,
      buildServer: BuildServerConnection,
      cancelPromise: Promise[Unit],
  )(implicit ec: ExecutionContext): Future[DebugServer] = {
    if (runningLocal.compareAndSet(false, true)) {
      val proxyServer = new ServerSocket(0, 50, localAddress)
      val host = InetAddresses.toUriString(proxyServer.getInetAddress)
      val port = proxyServer.getLocalPort
      proxyServer.setSoTimeout(10 * 1000)
      val uri = URI.create(s"tcp://$host:$port")

      val awaitClient = () => Future(proxyServer.accept())

      DebugRunner
        .open(
          sessionName,
          awaitClient,
          stacktraceAnalyzer,
          () => {
            val runParams =
              new b.RunParams(parameters.getTargets().asScala.head)
            runParams.setOriginId(ju.UUID.randomUUID().toString())

            /**
             * We set data and dataKind with the information about the main class to run,
             * otherwise if multiple main classes are found we would not know which one to run.
             */
            if (
              parameters
                .getDataKind() == b.DebugSessionParamsDataKind.SCALA_MAIN_CLASS
            ) {
              runParams.setDataKind(parameters.getDataKind())
              runParams.setData(parameters.getData())
            }
            val run = buildServer.buildTargetRun(runParams, cancelPromise)
            run.onComplete(_ => runningLocal.set(false))
            run
          },
          cancelPromise,
        )
        .flatMap { runner =>
          currentRunner.set(runner)
          runner.listen.map { code =>
            currentRunner.set(null)
            code
          }
        }

      val server = new DebugServer(
        sessionName,
        uri,
        () => Future.failed(new RuntimeException("No server connected")),
      )
      Future.successful(server)
    } else {
      Future.failed(
        new IllegalStateException("Cannot run multiple debug processes.")
      )
    }
  }

  private def localAddress: InetAddress = InetAddress.getByName("127.0.0.1")

  private def start(
      sessionName: String,
      parameters: b.DebugSessionParams,
      buildServer: BuildServerConnection,
      cancelPromise: Promise[Unit],
  )(implicit ec: ExecutionContext): Future[DebugServer] = {
    val proxyServer = new ServerSocket(0, 50, localAddress)
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
        val conn = buildServer
          .startDebugSession(parameters, cancelPromise)
          .map { uri =>
            val socket = connect(uri)
            connectedToServer.trySuccess(())
            socket
          }

        val startupTimeout = clientConfig.initialConfig.debugServerStartTimeout

        conn
          .withTimeout(startupTimeout, TimeUnit.SECONDS)
          .recover { case exception =>
            connectedToServer.tryFailure(exception)
            cancelPromise.trySuccess(())
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
        workDoneProgress,
        sourceMapper,
        compilations,
        targets,
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
      val params = new b.DebugSessionParams(singletonList(target))
      params.setDataKind(b.DebugSessionParamsDataKind.SCALA_MAIN_CLASS)
      params.setData(main.toJson)
      params
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
    semanticdbs()
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
          Future {
            val params = new b.DebugSessionParams(singletonList(buildTarget))
            params.setDataKind(b.TestParamsDataKind.SCALA_TEST_SUITES)
            params.setData(tests.asJava.toJson)
            params
          }

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

  def asSession(
      debugParams: DebugSessionParams
  )(implicit ec: ExecutionContext): Future[DebugSession] = {
    for {
      server <- start(debugParams),
    } yield {
      statusBar.addMessage("Started debug server!")
      DebugSession(server.sessionName, server.uri.toString)
    }
  }

  /**
   * Tries to discover the main class to run and returns
   * DebugSessionParams that contains the shellCommand field.
   * This is used so that clients can easily run the full command
   * if they want.
   */
  def runCommandDiscovery(
      unresolvedParams: DebugDiscoveryParams
  )(implicit ec: ExecutionContext): Future[b.DebugSessionParams] = {
    debugDiscovery(unresolvedParams).flatMap(enrichWithMainShellCommand)
  }

  private def enrichWithMainShellCommand(
      params: b.DebugSessionParams
  )(implicit ec: ExecutionContext): Future[b.DebugSessionParams] = {
    val future = params.getData() match {
      case json: JsonElement
          if params.getDataKind == b.DebugSessionParamsDataKind.SCALA_MAIN_CLASS =>
        json.as[b.ScalaMainClass] match {
          case Success(main) if params.getTargets().size > 0 =>
            val javaBinary = buildTargets
              .scalaTarget(params.getTargets().get(0))
              .flatMap(scalaTarget =>
                JavaBinary.javaBinaryFromPath(scalaTarget.jvmHome)
              )
              .orElse(userConfig().usedJavaBinary)
            buildTargetClasses
              .jvmRunEnvironment(params.getTargets().get(0))
              .map { envItem =>
                val updatedData = envItem.zip(javaBinary) match {
                  case None =>
                    main.toJson
                  case Some((env, javaHome)) =>
                    ExtendedScalaMainClass(
                      main,
                      env,
                      javaHome,
                      workspace,
                    ).toJson
                }
                params.setData(updatedData)
              }
          case _ => Future.unit
        }

      case _ => Future.unit
    }

    future.map { _ =>
      params
    }
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
        semanticdbs()
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
            val params = new b.DebugSessionParams(
              singletonList(target)
            )
            params.setDataKind(
              b.TestParamsDataKind.SCALA_TEST_SUITES
            )
            params.setData(tests.asJava.toJson)
            params
          }
      case (Some(TestTarget), Some(target)) =>
        Future {
          val params = new b.DebugSessionParams(
            singletonList(target)
          )
          params.setDataKind(b.TestParamsDataKind.SCALA_TEST_SUITES)
          params.setData(
            testClasses(target).values
              .map(_.fullyQualifiedName)
              .toList
              .asJava
              .toJson
          )
          params
        }
    }

    result.failed.foreach(reportErrors)
    result
  }

  def findMainClassAndItsBuildTarget(
      params: DebugUnresolvedMainClassParams
  ): Try[List[(ScalaMainClass, b.BuildTarget)]] =
    buildTargetClassesFinder
      .findMainClassAndItsBuildTarget(
        params.mainClass,
        Option(params.buildTarget),
      )

  def buildMainClassParams(
      foundClasses: List[(ScalaMainClass, b.BuildTarget)],
      params: DebugUnresolvedMainClassParams,
  )(implicit ec: ExecutionContext): Future[b.DebugSessionParams] = {
    val result = foundClasses match {
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

  def findTestClassAndItsBuildTarget(
      params: DebugUnresolvedTestClassParams
  ): Try[List[(String, b.BuildTarget)]] =
    buildTargetClassesFinder
      .findTestClassAndItsBuildTarget(
        params.testClass,
        Option(params.buildTarget),
      )

  def buildTestClassParams(
      targets: List[(String, b.BuildTarget)],
      params: DebugUnresolvedTestClassParams,
  )(implicit ec: ExecutionContext): Future[b.DebugSessionParams] = {
    val result = targets match {
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
          val jvmOpts =
            JvmOpts.fromWorkspaceOrEnvForTest(workspace).getOrElse(Nil)
          val scalaTestSuite = new b.ScalaTestSuites(
            List(
              new b.ScalaTestSuiteSelection(params.testClass, Nil.asJava)
            ).asJava,
            Option(params.jvmOptions)
              .map(jvmOpts ++ _.asScala)
              .getOrElse(jvmOpts)
              .asJava,
            (envFromFile ::: env).asJava,
          )
          val debugParams = new b.DebugSessionParams(
            singletonList(target.getId())
          )
          debugParams.setDataKind(
            b.TestParamsDataKind.SCALA_TEST_SUITES_SELECTION
          )
          debugParams.setData(scalaTestSuite.toJson)
          debugParams
        }
      // should not really happen due to
      // `findMainClassAndItsBuildTarget` succeeding with non-empty list
      case Nil => Future.failed(new ju.NoSuchElementException(params.testClass))
    }
    result.failed.foreach(reportErrors)
    result
  }

  def createDebugSession(
      target: b.BuildTargetIdentifier
  ): Future[DebugSessionParams] =
    Future.successful {
      val params = new b.DebugSessionParams(
        singletonList(target)
      )
      params.setDataKind(b.DebugSessionParamsDataKind.SCALA_ATTACH_REMOTE)
      params.setData(().toJson)
      params
    }

  def startTestSuite(
      buildTarget: b.BuildTarget,
      request: ScalaTestSuitesDebugRequest,
  )(implicit ec: ExecutionContext): Future[DebugSessionParams] = {
    def makeDebugSession() = {
      val jvmOpts = JvmOpts.fromWorkspaceOrEnvForTest(workspace).getOrElse(Nil)
      val debugSession =
        if (supportsTestSelection(request.target)) {
          val testSuites =
            request.requestData.copy(
              suites = request.requestData.suites.map { suite =>
                testProvider.getFramework(buildTarget, suite) match {
                  case JUnit4 | MUnit =>
                    suite.copy(tests = suite.tests.map(escapeTestName))
                  case _ => suite
                }
              },
              jvmOptions = jvmOpts.asJava,
            )
          val params = new b.DebugSessionParams(
            singletonList(buildTarget.getId)
          )
          params.setDataKind(
            b.TestParamsDataKind.SCALA_TEST_SUITES_SELECTION
          )
          params.setData(testSuites.toJson)
          params
        } else {
          val params = new b.DebugSessionParams(
            singletonList(buildTarget.getId)
          )
          params.setDataKind(b.TestParamsDataKind.SCALA_TEST_SUITES)
          params.setData(request.requestData.suites.map(_.className).toJson)
          params
        }
      Future.successful(debugSession)
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
    case e @ SemanticDbNotFoundException =>
      languageClient.metalsStatus(
        MetalsStatusParams(
          text = s"${clientConfig.icons.alert}Build misconfiguration",
          tooltip = e.getMessage(),
          command = ServerCommands.RunDoctor.id,
        )
      )
    case _ =>
  }

  private def parseSessionName(
      parameters: b.DebugSessionParams
  ): Try[String] = {
    parameters.getData match {
      case json: JsonElement =>
        parameters.getDataKind match {
          case b.DebugSessionParamsDataKind.SCALA_MAIN_CLASS =>
            json.as[b.ScalaMainClass].map(_.getClassName)
          case b.TestParamsDataKind.SCALA_TEST_SUITES =>
            json.as[ju.List[String]].map(_.asScala.sorted.mkString(";"))
          case b.DebugSessionParamsDataKind.SCALA_ATTACH_REMOTE =>
            Success("attach-remote-debug-session")
          case b.TestParamsDataKind.SCALA_TEST_SUITES_SELECTION =>
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

  def retryAfterRebuild[A](previousResult: Try[A], f: () => Try[A])(implicit
      ec: ExecutionContext
  ): Future[Try[A]] =
    previousResult match {
      case Failure(ClassNotFoundInBuildTargetException(_, buildTarget)) =>
        val target = Seq(buildTarget.getId())
        for {
          _ <- compilations.compileTargets(target)
          _ <- buildTargetClasses.rebuildIndex(target)
          result <- Future(f())
        } yield result
      case Failure(_: NoClassFoundException) =>
        val allTargetIds = buildTargets.allBuildTargetIds
        for {
          _ <- compilations.compileTargets(allTargetIds)
          _ <- buildTargetClasses.rebuildIndex(allTargetIds)
          result <- Future(f())
        } yield result
      case result => Future.successful(result)
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

  private def exceptionOrder(t: Throwable): Int = t match {
    /* Validation errors, should shown first as they are relevant for each build target.
     */
    case DotEnvFileParser.InvalidEnvFileException(_) => 0
    case NoRunOptionException => 1
    case _: RunType.UnknownRunTypeException => 2
    /* Target found, but class not, means that we managed to find the proper workspace folder
     */
    case _: ClassNotFoundInBuildTargetException => 3
    case _: BuildTargetContainsNoMainException => 4
    case _: NoTestsFoundException => 5
    /* These exception will show up and it's hard to pinpoint which folder we should be in
     * since we failed to fin the exception anywhere. Probably just showing the error is enough.
     */
    case _: BuildTargetNotFoundForPathException => 6
    case _: NoClassFoundException => 7
    case _: BuildTargetNotFoundException => 8
    case _ => 9
  }

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

  val specialChars: Set[Char] = ".+*?^()[]{}|&$".toSet

  def escapeTestName(testName: String): String =
    testName.flatMap {
      case c if specialChars(c) => s"\\$c"
      case c => s"$c"
    }

  sealed abstract class ClassSearch[A](debugProvider: DebugProvider)(implicit
      ec: ExecutionContext
  ) {
    private val searchPromise = Promise[Try[A]]()
    protected def search(): Try[A]
    protected def dapSessionParams(res: A): Future[DebugSessionParams]
    def createDapSession(args: A): Future[DebugSession] =
      dapSessionParams(args).flatMap(debugProvider.asSession(_))
    def searchResult: Future[(Try[A], ClassSearch[A])] = {
      Future {
        val resolved = search()
        searchPromise.trySuccess(resolved)
      }
      searchPromise.future.map(e => (e, this))
    }
    def retrySearchResult: Future[(Try[A], ClassSearch[A])] =
      searchPromise.future
        .flatMap(debugProvider.retryAfterRebuild(_, search))
        .map((_, this))
  }

  final class MainClassSearch(
      debugProvider: DebugProvider,
      params: DebugUnresolvedMainClassParams,
  )(implicit ec: ExecutionContext)
      extends ClassSearch[List[(ScalaMainClass, b.BuildTarget)]](
        debugProvider
      ) {
    override protected def search()
        : Try[List[(ScalaMainClass, b.BuildTarget)]] =
      debugProvider.findMainClassAndItsBuildTarget(params)
    override protected def dapSessionParams(
        res: List[(ScalaMainClass, b.BuildTarget)]
    ): Future[DebugSessionParams] =
      debugProvider.buildMainClassParams(res, params)
  }

  final class TestClassSearch(
      debugProvider: DebugProvider,
      params: DebugUnresolvedTestClassParams,
  )(implicit ec: ExecutionContext)
      extends ClassSearch[List[(String, b.BuildTarget)]](debugProvider) {
    override protected def search(): Try[List[(String, b.BuildTarget)]] =
      debugProvider.findTestClassAndItsBuildTarget(params)
    override protected def dapSessionParams(
        res: List[(String, b.BuildTarget)]
    ): Future[DebugSessionParams] =
      debugProvider.buildTestClassParams(res, params)
  }

  def getResultFromSearches[A](
      searches: List[ClassSearch[A]]
  )(implicit ec: ExecutionContext): Future[DebugSession] =
    Future
      .sequence(searches.map(_.searchResult))
      .getFirstOrError
      .recoverWith { case _ =>
        Future.sequence(searches.map(_.retrySearchResult)).getFirstOrError
      }

  private implicit class FindFirstDebugSession[A](
      from: Future[List[(Try[A], ClassSearch[A])]]
  ) {
    def getFirstOrError(implicit ec: ExecutionContext): Future[DebugSession] =
      from.flatMap { all =>
        def mostRelevantError() = all
          .collect { case (Failure(error), _) =>
            error
          }
          .sortBy(DebugProvider.exceptionOrder)
          .head
        all
          .collectFirst { case (Success(dapSession), search) =>
            search.createDapSession(dapSession)
          }
          .getOrElse(Future.failed(mostRelevantError()))
      }
  }
}
