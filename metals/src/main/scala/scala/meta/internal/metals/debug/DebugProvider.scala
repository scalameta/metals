package scala.meta.internal.metals.debug

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.Collections.singletonList
import java.util.concurrent.TimeUnit
import java.{util => ju}

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.Duration
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
import scala.meta.internal.metals.DebugSession
import scala.meta.internal.metals.DebugUnresolvedMainClassParams
import scala.meta.internal.metals.DebugUnresolvedTestClassParams
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
import scala.meta.internal.metals.clients.language.MetalsStatusParams
import scala.meta.internal.metals.config.RunType
import scala.meta.internal.metals.debug.DiscoveryFailures._
import scala.meta.internal.metals.debug.server.AttachRemoteDebugAdapter
import scala.meta.internal.metals.debug.server.DebugLogger
import scala.meta.internal.metals.debug.server.DebugeeParamsCreator
import scala.meta.internal.metals.debug.server.Discovered
import scala.meta.internal.metals.debug.server.MainClassDebugAdapter
import scala.meta.internal.metals.debug.server.MetalsDebugToolsResolver
import scala.meta.internal.metals.debug.server.MetalsDebuggee
import scala.meta.internal.metals.debug.server.TestSuiteDebugAdapter
import scala.meta.internal.metals.testProvider.TestSuitesProvider
import scala.meta.io.AbsolutePath

import bloop.config.Config
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.DebugSessionParams
import ch.epfl.scala.bsp4j.ScalaMainClass
import ch.epfl.scala.{bsp4j => b}
import ch.epfl.scala.{debugadapter => dap}
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
    buildTargetClassesFinder: BuildTargetClassesFinder,
    stacktraceAnalyzer: StacktraceAnalyzer,
    clientConfig: ClientConfiguration,
    compilers: Compilers,
    statusBar: StatusBar,
    workDoneProgress: WorkDoneProgress,
    sourceMapper: SourceMapper,
    userConfig: () => UserConfiguration,
    testProvider: TestSuitesProvider,
)(implicit ec: ExecutionContext)
    extends Cancelable
    with LogForwarder {

  import DebugProvider._

  val debugConfigCreator = new DebugeeParamsCreator(buildTargetClasses)

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

      compilations
        .compilationFinished(targets, compileInverseDependencies = false)
        .flatMap { _ =>
          val conn =
            startDebugSession(buildServer, parameters, cancelPromise)
              .map { uri =>
                val socket = connect(uri)
                connectedToServer.trySuccess(())
                socket
              }

          val startupTimeout =
            clientConfig.initialConfig.debugServerStartTimeout

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

  private def startDebugSession(
      buildServer: BuildServerConnection,
      params: DebugSessionParams,
      cancelPromise: Promise[Unit],
  )(implicit ec: ExecutionContext) =
    if (buildServer.isDebuggingProvider || buildServer.isSbt) {
      buildServer.startDebugSession(params, cancelPromise)
    } else {
      def getDebugee: Either[String, Future[MetalsDebuggee]] = {
        def buildTarget = params
          .getTargets()
          .asScala
          .headOption
          .toRight(s"Missing build target in debug params.")
        params.getDataKind() match {
          case b.DebugSessionParamsDataKind.SCALA_MAIN_CLASS =>
            for {
              id <- buildTarget
              projectInfo <- debugConfigCreator.create(
                id,
                cancelPromise,
                isTests = false,
              )
              scalaMainClass <- params.asScalaMainClass()
            } yield projectInfo.map(
              new MainClassDebugAdapter(
                workspace,
                scalaMainClass,
                _,
                userConfig().javaHome,
              )
            )
          case (b.TestParamsDataKind.SCALA_TEST_SUITES_SELECTION |
              b.TestParamsDataKind.SCALA_TEST_SUITES) =>
            for {
              id <- buildTarget
              projectInfo <- debugConfigCreator.create(
                id,
                cancelPromise,
                isTests = true,
              )
              testSuites <- params.asScalaTestSuites()
            } yield {
              for {
                discovered <- discoverTests(id, testSuites)
                project <- projectInfo
              } yield new TestSuiteDebugAdapter(
                workspace,
                testSuites,
                project,
                userConfig().javaHome,
                discovered,
              )
            }
          case b.DebugSessionParamsDataKind.SCALA_ATTACH_REMOTE =>
            for {
              id <- buildTarget
              projectInfo <- debugConfigCreator.create(
                id,
                cancelPromise,
                isTests = false,
              )
            } yield projectInfo.map(project =>
              new AttachRemoteDebugAdapter(project, userConfig().javaHome)
            )
          case kind =>
            Left(s"Starting debug session for $kind in not supported.")
        }
      }

      for {
        _ <- compilations.compileTargets(params.getTargets().asScala.toSeq)
        debuggee <- getDebugee match {
          case Right(debuggee) => debuggee
          case Left(errorMessage) =>
            Future.failed(new RuntimeException(errorMessage))
        }
      } yield {
        val dapLogger = new DebugLogger()
        val resolver = new MetalsDebugToolsResolver()
        val handler =
          dap.DebugServer.run(
            debuggee,
            resolver,
            dapLogger,
            gracePeriod = Duration(
              clientConfig.initialConfig.debuggeeGracePeriod,
              TimeUnit.SECONDS,
            ),
          )
        handler.uri
      }
    }

  def discoverTests(
      id: BuildTargetIdentifier,
      testClasses: b.ScalaTestSuites,
  ): Future[Map[Config.TestFramework, List[Discovered]]] = {
    scribe.debug(
      s"Discovering tests for build target: $id, test classes: ${testClasses.getSuites().asScala.map(_.getClassName()).mkString(", ")}"
    )
    val symbolInfosList =
      for {
        selection <- testClasses.getSuites().asScala.toList
        (sym, info) <- buildTargetClasses.getTestClasses(
          selection.getClassName(),
          id,
        )
      } yield compilers.info(id, sym).map(_.map(pcInfo => (info, pcInfo)))

    Future.sequence(symbolInfosList).map { infos =>
      scribe.debug(
        s"Found infos about: ${infos.flatten.map(_._2.symbol).mkString(", ")} test classes"
      )
      val allInfo = infos.flatten
      if (allInfo.isEmpty) {
        val suitesString =
          testClasses.getSuites().asScala.map(_.getClassName()).mkString(", ")
        scribe.error(
          s"Could not get information from the compiler about $suitesString"
        )
      }
      infos.flatten.groupBy(_._1.framework).map {
        case (framework, testSuites) =>
          (
            framework,
            testSuites.map { case (testInfo, pcInfo) =>
              new Discovered(
                pcInfo.symbol,
                testInfo.fullyQualifiedName,
                pcInfo.recursiveParents.map(_.symbolToFullyQualifiedName).toSet,
                (pcInfo.annotations ++ pcInfo.memberDefsAnnotations).toSet,
                isModule = pcInfo.symbol.endsWith("."),
              )
            },
          )
      }
    }
  }

  private def buildServerConnect(parameters: b.DebugSessionParams) = for {
    targetId <- parameters.getTargets().asScala.headOption
    buildServer <- buildTargets.buildServerOf(targetId)
  } yield buildServer

  private def supportsTestSelection(targetId: b.BuildTargetIdentifier) = {
    buildTargets.buildServerOf(targetId).exists(_.supportsTestSelection)
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
      server <- start(debugParams)
    } yield {
      statusBar.addMessage("Started debug server!")
      DebugSession(server.sessionName, server.uri.toString)
    }
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

        val env = Option(params.env).toList.flatMap(DebugProvider.createEnvList)
        createMainParams(
          workspace,
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
        val env = Option(params.env).toList.flatMap(DebugProvider.createEnvList)

        DebugProvider.envFromFile(workspace, Option(params.envFile)).map {
          envFromFile =>
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
                  case Config.TestFramework.JUnit |
                      Config.TestFramework.munit =>
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
          text = s"${clientConfig.icons().alert}Build misconfiguration",
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
        val target =
          buildTargets.findByDisplayNameOrUri(buildTarget).map(_.getId()).toSeq
        for {
          _ <- compilations.compileTargets(target)
          _ <- buildTargetClasses.rebuildIndex(target)
          result <- Future(f())
        } yield result
      case Failure(_: NoMainClassFoundException) =>
        val activeTargetIds = buildTargets.activeBuildTargetIds
        for {
          _ <- compilations.compileTargets(activeTargetIds)
          _ <- buildTargetClasses.rebuildIndex(activeTargetIds)
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

  def createEnvList(env: ju.Map[String, String]): List[String] = {
    env.asScala.map { case (key, value) =>
      s"$key=$value"
    }.toList
  }

  def envFromFile(
      workspace: AbsolutePath,
      envFile: Option[String],
  )(implicit ec: ExecutionContext): Future[List[String]] =
    envFile
      .map { file =>
        val path = AbsolutePath(file)(workspace)
        DotEnvFileParser
          .parse(path)
          .map(_.map { case (key, value) => s"$key=$value" }.toList)
      }
      .getOrElse(Future.successful(List.empty))

  def createMainParams(
      workspace: AbsolutePath,
      main: ScalaMainClass,
      target: BuildTargetIdentifier,
      args: Option[ju.List[String]],
      jvmOptions: Option[ju.List[String]],
      env: List[String],
      envFile: Option[String],
  )(implicit ec: ExecutionContext): Future[DebugSessionParams] = {
    main.setArguments(args.getOrElse(ju.Collections.emptyList()))
    main.setJvmOptions(
      jvmOptions.getOrElse(ju.Collections.emptyList())
    )
    envFromFile(workspace, envFile).map { envFromFile =>
      main.setEnvironmentVariables((envFromFile ::: env).asJava)
      val params = new b.DebugSessionParams(singletonList(target))
      params.setDataKind(b.DebugSessionParamsDataKind.SCALA_MAIN_CLASS)
      params.setData(main.toJson)
      params
    }
  }

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
    case _: NoMainClassFoundException => 7
    case _: BuildTargetNotFoundException => 8
    case _ => 9
  }

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
