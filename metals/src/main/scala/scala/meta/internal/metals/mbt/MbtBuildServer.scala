package scala.meta.internal.metals.mbt

import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.URI
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference

import scala.build.bsp.WrappedSourcesResult
import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.jdk.CollectionConverters._
import scala.util.Failure
import scala.util.Success

import scala.meta.internal.bsp.ConnectionBspStatus
import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.BuildServerConnection
import scala.meta.internal.metals.BuildServerConnectionFactory
import scala.meta.internal.metals.Cancelable
import scala.meta.internal.metals.ClosableOutputStream
import scala.meta.internal.metals.DismissedNotifications
import scala.meta.internal.metals.JsonParser._
import scala.meta.internal.metals.MetalsBuildClient
import scala.meta.internal.metals.MetalsBuildServer
import scala.meta.internal.metals.MetalsEnrichments.XtensionAbsolutePathBuffers
import scala.meta.internal.metals.MetalsEnrichments.XtensionDebugSessionParams
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.QuietInputStream
import scala.meta.internal.metals.ScalaVersionSelector
import scala.meta.internal.metals.SocketConnection
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.WorkDoneProgress
import scala.meta.internal.metals.clients.language.ConfiguredLanguageClient
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BspConnectionDetails
import ch.epfl.scala.bsp4j.BuildClient
import ch.epfl.scala.bsp4j.BuildServerCapabilities
import ch.epfl.scala.bsp4j.BuildTargetEvent
import ch.epfl.scala.bsp4j.BuildTargetEventKind
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.CleanCacheParams
import ch.epfl.scala.bsp4j.CleanCacheResult
import ch.epfl.scala.bsp4j.CompileParams
import ch.epfl.scala.bsp4j.CompileProvider
import ch.epfl.scala.bsp4j.CompileResult
import ch.epfl.scala.bsp4j.DebugProvider
import ch.epfl.scala.bsp4j.DebugSessionAddress
import ch.epfl.scala.bsp4j.DebugSessionParams
import ch.epfl.scala.bsp4j.DebugSessionParamsDataKind
import ch.epfl.scala.bsp4j.DependencyModulesParams
import ch.epfl.scala.bsp4j.DependencyModulesResult
import ch.epfl.scala.bsp4j.DependencySourcesParams
import ch.epfl.scala.bsp4j.DependencySourcesResult
import ch.epfl.scala.bsp4j.DidChangeBuildTarget
import ch.epfl.scala.bsp4j.InitializeBuildParams
import ch.epfl.scala.bsp4j.InitializeBuildResult
import ch.epfl.scala.bsp4j.InverseSourcesParams
import ch.epfl.scala.bsp4j.InverseSourcesResult
import ch.epfl.scala.bsp4j.JavacOptionsParams
import ch.epfl.scala.bsp4j.JavacOptionsResult
import ch.epfl.scala.bsp4j.JvmCompileClasspathItem
import ch.epfl.scala.bsp4j.JvmCompileClasspathParams
import ch.epfl.scala.bsp4j.JvmCompileClasspathResult
import ch.epfl.scala.bsp4j.JvmRunEnvironmentParams
import ch.epfl.scala.bsp4j.JvmRunEnvironmentResult
import ch.epfl.scala.bsp4j.JvmTestEnvironmentParams
import ch.epfl.scala.bsp4j.JvmTestEnvironmentResult
import ch.epfl.scala.bsp4j.OutputPathsParams
import ch.epfl.scala.bsp4j.OutputPathsResult
import ch.epfl.scala.bsp4j.PrintParams
import ch.epfl.scala.bsp4j.ReadParams
import ch.epfl.scala.bsp4j.ResourcesParams
import ch.epfl.scala.bsp4j.ResourcesResult
import ch.epfl.scala.bsp4j.RunParams
import ch.epfl.scala.bsp4j.RunProvider
import ch.epfl.scala.bsp4j.RunResult
import ch.epfl.scala.bsp4j.ScalaMainClass
import ch.epfl.scala.bsp4j.ScalaMainClassesParams
import ch.epfl.scala.bsp4j.ScalaMainClassesResult
import ch.epfl.scala.bsp4j.ScalaTestClassesParams
import ch.epfl.scala.bsp4j.ScalaTestClassesResult
import ch.epfl.scala.bsp4j.ScalaTestSuiteSelection
import ch.epfl.scala.bsp4j.ScalaTestSuites
import ch.epfl.scala.bsp4j.ScalacOptionsParams
import ch.epfl.scala.bsp4j.ScalacOptionsResult
import ch.epfl.scala.bsp4j.SourcesParams
import ch.epfl.scala.bsp4j.SourcesResult
import ch.epfl.scala.bsp4j.StatusCode
import ch.epfl.scala.bsp4j.TaskId
import ch.epfl.scala.bsp4j.TestParams
import ch.epfl.scala.bsp4j.TestParamsDataKind
import ch.epfl.scala.bsp4j.TestProvider
import ch.epfl.scala.bsp4j.TestResult
import ch.epfl.scala.bsp4j.WorkspaceBuildTargetsResult
import org.eclipse.lsp4j.jsonrpc.Launcher

final class MbtBuildServer(
    workspace: AbsolutePath,
    build: () => MbtBuild,
    scalaVersionSelector: ScalaVersionSelector,
    debugStarter: Option[MbtDebugSessionStarter],
)(implicit ec: ExecutionContext)
    extends MetalsBuildServer {

  private val buildClient = new AtomicReference[BuildClient]()
  private val importedBuild =
    new AtomicReference[Seq[MbtTarget]](build().mbtTargets)

  def onConnectWithClient(client: BuildClient): Unit =
    buildClient.set(client)

  private def importedBuildTargets: Seq[MbtTarget] = importedBuild.get()

  private def shouldScanGlobDirectory(
      relativeDirectory: Path,
      targets: Seq[MbtTarget],
  ): Boolean =
    targets.exists(_.shouldScanGlobDirectory(relativeDirectory))

  private def globbedSourceFiles(
      targets: Seq[MbtTarget]
  ): Map[BuildTargetIdentifier, Seq[AbsolutePath]] = {
    val targetsWithGlobs = targets.filter(_.globMatchers.nonEmpty)
    if (targetsWithGlobs.isEmpty) Map.empty
    else {
      val matchedFiles =
        mutable.LinkedHashMap.empty[
          BuildTargetIdentifier,
          mutable.LinkedHashSet[AbsolutePath],
        ]
      Files.walkFileTree(
        workspace.toNIO,
        new SimpleFileVisitor[Path] {
          override def preVisitDirectory(
              dir: Path,
              attrs: BasicFileAttributes,
          ): FileVisitResult = {
            val relativeDirectory =
              if (dir == workspace.toNIO) Paths.get("")
              else workspace.toNIO.relativize(dir)
            if (shouldScanGlobDirectory(relativeDirectory, targetsWithGlobs))
              FileVisitResult.CONTINUE
            else FileVisitResult.SKIP_SUBTREE
          }

          override def visitFile(
              path: Path,
              attrs: BasicFileAttributes,
          ): FileVisitResult = {
            val file = AbsolutePath(path)
            if (attrs.isRegularFile && file.isScalaOrJava) {
              val relativePath = workspace.toNIO.relativize(path)
              targetsWithGlobs.foreach { target =>
                if (
                  !target.containsStableSource(workspace, file) &&
                  target.globMatchers.exists(_.matcher.matches(relativePath))
                ) {
                  matchedFiles
                    .getOrElseUpdate(target.id, mutable.LinkedHashSet.empty)
                    .add(file)
                }
              }
            }
            FileVisitResult.CONTINUE
          }
        },
      )
      matchedFiles.view.mapValues(_.toSeq).toMap
    }
  }
  override def onRunReadStdin(params: ReadParams): Unit = ()

  override def workspaceSync(
      params: scala.meta.internal.bsp.sync.WorkspaceSyncParams
  ): java.util.concurrent.CompletableFuture[
    scala.meta.internal.bsp.sync.WorkspaceSyncResult
  ] = CompletableFuture.failedFuture(
    new UnsupportedOperationException(
      "MBT build server does not support 'workspaceSync'."
    )
  )

  override def buildInitialize(
      params: InitializeBuildParams
  ): CompletableFuture[InitializeBuildResult] =
    CompletableFuture.completedFuture {
      val capabilities = new BuildServerCapabilities
      capabilities.setCanReload(true)
      capabilities.setDependencySourcesProvider(true)
      capabilities.setDependencyModulesProvider(true)
      capabilities.setInverseSourcesProvider(true)
      capabilities.setCompileProvider(
        new CompileProvider(List("scala", "java").asJava)
      )
      capabilities.setResourcesProvider(false)
      capabilities.setJvmCompileClasspathProvider(true)
      if (debugStarter.isDefined) {
        capabilities.setDebugProvider(
          new DebugProvider(List("scala", "java").asJava)
        )
        capabilities.setRunProvider(
          new RunProvider(List("scala", "java").asJava)
        )
        capabilities.setTestProvider(
          new TestProvider(List("scala", "java").asJava)
        )
      }
      new InitializeBuildResult(
        MbtBuildServer.name,
        BuildInfo.metalsVersion,
        BuildInfo.bspVersion,
        capabilities,
      )
    }

  override def onBuildInitialized(): Unit = ()

  override def buildShutdown(): CompletableFuture[AnyRef] =
    CompletableFuture.completedFuture(null)

  override def onBuildExit(): Unit = ()

  override def workspaceReload(): CompletableFuture[Object] = {
    val newTargets = build().mbtTargets
    importedBuild.set(newTargets)
    Option(buildClient.get()).foreach { client =>
      val events = newTargets.map { target =>
        val event = new BuildTargetEvent(target.id)
        event.setKind(BuildTargetEventKind.CHANGED)
        event
      }
      client.onBuildTargetDidChange(
        new DidChangeBuildTarget(events.asJava)
      )
    }
    CompletableFuture.completedFuture(null)
  }

  override def workspaceBuildTargets()
      : CompletableFuture[WorkspaceBuildTargetsResult] =
    CompletableFuture.completedFuture(
      new WorkspaceBuildTargetsResult(
        importedBuildTargets
          .map(_.buildTarget(workspace, scalaVersionSelector))
          .asJava
      )
    )

  override def buildTargetSources(
      params: SourcesParams
  ): CompletableFuture[SourcesResult] = {
    val requestedTargets = params.getTargets.asScala.toSet
    val targets = importedBuildTargets.filter(t => requestedTargets(t.id))
    val globbedSources = globbedSourceFiles(targets)
    CompletableFuture.completedFuture(
      new SourcesResult(
        targets
          .map(target =>
            target.sourcesItem(
              workspace,
              globbedSources.getOrElse(target.id, Nil),
            )
          )
          .asJava
      )
    )
  }
  override def buildTargetInverseSources(
      params: InverseSourcesParams
  ): CompletableFuture[InverseSourcesResult] =
    CompletableFuture.completedFuture {
      val path =
        AbsolutePath(Paths.get(URI.create(params.getTextDocument.getUri)))
      new InverseSourcesResult(
        importedBuildTargets
          .filter(_.containsSource(workspace, path))
          .map(_.id)
          .asJava
      )
    }

  override def buildTargetDependencySources(
      params: DependencySourcesParams
  ): CompletableFuture[DependencySourcesResult] = {
    val requestedTargets = params.getTargets.asScala.toSet
    CompletableFuture.completedFuture(
      new DependencySourcesResult(
        importedBuildTargets
          .filter(t => requestedTargets(t.id))
          .map(_.dependencySourcesItem)
          .asJava
      )
    )
  }

  override def buildTargetResources(
      params: ResourcesParams
  ): CompletableFuture[ResourcesResult] =
    CompletableFuture.failedFuture(
      new UnsupportedOperationException(
        "MBT build server does not support 'buildTarget/resources'."
      )
    )

  override def buildTargetOutputPaths(
      params: OutputPathsParams
  ): CompletableFuture[OutputPathsResult] =
    CompletableFuture.failedFuture(
      new UnsupportedOperationException(
        "MBT build server does not support 'buildTarget/outputPaths'."
      )
    )

  override def buildTargetCompile(
      params: CompileParams
  ): CompletableFuture[CompileResult] = {
    val result = new CompletableFuture[CompileResult]()
    debugStarter match {
      case None =>
        result.complete(new CompileResult(StatusCode.OK))
      case Some(starter) =>
        val targets = params.getTargets.asScala.toSeq.flatMap { id =>
          importedBuildTargets.find(_.id == id)
        }
        if (targets.isEmpty) {
          result.complete(new CompileResult(StatusCode.OK))
        } else {
          val futures = targets.map { target =>
            starter.compile(
              target,
              workspace,
              out = line => scribe.info(s"[mbt-compile] $line"),
              err = line => scribe.warn(s"[mbt-compile] $line"),
            )
          }
          Future
            .sequence(futures)
            .map { codes =>
              val status =
                if (codes.forall(_ == 0)) StatusCode.OK else StatusCode.ERROR
              result.complete(new CompileResult(status))
            }
            .recover { case ex =>
              scribe.error("MBT compile failed", ex)
              result.complete(new CompileResult(StatusCode.ERROR))
            }
        }
    }
    result
  }

  override def buildTargetTest(
      params: TestParams
  ): CompletableFuture[TestResult] = {
    val result = new CompletableFuture[TestResult]()
    debugStarter match {
      case None =>
        result.completeExceptionally(
          new UnsupportedOperationException(
            "MBT build server has no test session starter configured."
          )
        )
      case Some(starter) =>
        val outcome: Either[String, Future[Int]] = for {
          testSuites <- asScalaTestSuites(params)
          target <- importedBuildTargets
            .find(_.id == params.getTargets.asScala.headOption.orNull)
            .toRight(
              s"buildTarget/test: no MBT target for ${params.getTargets}"
            )
        } yield starter.test(
          target,
          testSuites,
          workspace,
          line => testPrint(params, "mbt-test", line, isError = false),
          line => testPrint(params, "mbt-test", line, isError = true),
        )

        outcome match {
          case Left(error) =>
            result.completeExceptionally(new IllegalArgumentException(error))
          case Right(future) =>
            future.onComplete {
              case Success(0) =>
                result.complete(new TestResult(StatusCode.OK))
              case Success(_) =>
                result.complete(new TestResult(StatusCode.ERROR))
              case Failure(ex) =>
                result.completeExceptionally(ex)
            }
        }
    }
    result
  }

  private def asScalaTestSuites(
      params: TestParams
  ): Either[String, ScalaTestSuites] =
    params.getDataKind match {
      case TestParamsDataKind.SCALA_TEST_SUITES_SELECTION =>
        params.getData match {
          case json: com.google.gson.JsonElement =>
            json
              .as[ScalaTestSuites]
              .toOption
              .toRight("buildTarget/test: cannot decode ScalaTestSuites")
          case _ =>
            Left("buildTarget/test: expected ScalaTestSuites data")
        }
      case TestParamsDataKind.SCALA_TEST_SUITES =>
        params.getData match {
          case json: com.google.gson.JsonElement =>
            json
              .as[java.util.List[String]]
              .toOption
              .map { tests =>
                val suites = tests.asScala
                  .map(new ScalaTestSuiteSelection(_, List.empty.asJava))
                  .asJava
                new ScalaTestSuites(
                  suites,
                  List.empty.asJava,
                  List.empty.asJava,
                )
              }
              .toRight("buildTarget/test: cannot decode test class names")
          case _ =>
            Left("buildTarget/test: expected test class names list")
        }
      case _ =>
        Left(
          s"buildTarget/test: unsupported data kind: ${params.getDataKind}"
        )
    }

  private def testPrint(
      params: TestParams,
      taskId: String,
      message: String,
      isError: Boolean,
  ): Unit = {
    Option(buildClient.get()).foreach { client =>
      val originId =
        Option(params.getOriginId).getOrElse("metals-mbt-test")
      val printParams = new PrintParams(originId, message + "\n")
      printParams.setTask(new TaskId(taskId))
      if (isError) client.onRunPrintStderr(printParams)
      else client.onRunPrintStdout(printParams)
    }
  }

  override def buildTargetRun(
      params: RunParams
  ): CompletableFuture[RunResult] = {
    val result = new CompletableFuture[RunResult]()
    debugStarter match {
      case None =>
        result.completeExceptionally(
          new UnsupportedOperationException(
            "MBT build server has no run session starter configured."
          )
        )
      case Some(starter) =>
        val outcome: Either[String, Future[Int]] = for {
          mainClass <- asScalaMainClass(params)
          target <- importedBuildTargets
            .find(_.id == params.getTarget)
            .toRight(s"buildTarget/run: no MBT target for ${params.getTarget}")
        } yield starter.run(
          target,
          mainClass,
          workspace,
          line => runPrint(params, "mbt-run", line, isError = false),
          line => runPrint(params, "mbt-run", line, isError = true),
        )

        outcome match {
          case Left(error) =>
            result.completeExceptionally(new IllegalArgumentException(error))
          case Right(future) =>
            future.onComplete {
              case Success(0) =>
                result.complete(new RunResult(StatusCode.OK))
              case Success(_) =>
                result.complete(new RunResult(StatusCode.ERROR))
              case Failure(ex) =>
                result.completeExceptionally(ex)
            }
        }
    }
    result
  }

  private def asScalaMainClass(
      params: RunParams
  ): Either[String, ScalaMainClass] =
    params.getData match {
      case json: com.google.gson.JsonElement
          if params.getDataKind == DebugSessionParamsDataKind.SCALA_MAIN_CLASS =>
        json
          .as[ScalaMainClass]
          .toOption
          .toRight("buildTarget/run: cannot decode ScalaMainClass")
      case _ =>
        Left("buildTarget/run: expected ScalaMainClass data")
    }

  private def runPrint(
      params: RunParams,
      taskId: String,
      message: String,
      isError: Boolean,
  ): Unit = {
    Option(buildClient.get()).foreach { client =>
      val originId =
        Option(params.getOriginId).getOrElse("metals-mbt-run")
      val printParams = new PrintParams(originId, message + "\n")
      printParams.setTask(new TaskId(taskId))
      if (isError) client.onRunPrintStderr(printParams)
      else client.onRunPrintStdout(printParams)
    }
  }

  override def buildTargetCleanCache(
      params: CleanCacheParams
  ): CompletableFuture[CleanCacheResult] =
    CompletableFuture.completedFuture {
      val result = new CleanCacheResult(true)
      result.setMessage("MBT build server is read-only.")
      result
    }

  override def buildTargetScalacOptions(
      params: ScalacOptionsParams
  ): CompletableFuture[ScalacOptionsResult] = {
    val requestedTargets = params.getTargets.asScala.toSet
    CompletableFuture.completedFuture(
      new ScalacOptionsResult(
        importedBuildTargets
          .filter(t => requestedTargets(t.id))
          .map(_.scalacOptionsItem(workspace))
          .asJava
      )
    )
  }

  override def buildTargetJavacOptions(
      params: JavacOptionsParams
  ): CompletableFuture[JavacOptionsResult] = {
    val requestedTargets = params.getTargets.asScala.toSet
    CompletableFuture.completedFuture(
      new JavacOptionsResult(
        importedBuildTargets
          .filter(t => requestedTargets(t.id))
          .map(_.javacOptionsItem(workspace))
          .asJava
      )
    )
  }

  override def buildTargetScalaMainClasses(
      params: ScalaMainClassesParams
  ): CompletableFuture[ScalaMainClassesResult] =
    CompletableFuture.completedFuture(
      new ScalaMainClassesResult(List.empty.asJava)
    )

  override def buildTargetScalaTestClasses(
      params: ScalaTestClassesParams
  ): CompletableFuture[ScalaTestClassesResult] =
    CompletableFuture.completedFuture(
      new ScalaTestClassesResult(List.empty.asJava)
    )

  override def buildTargetDependencyModules(
      params: DependencyModulesParams
  ): CompletableFuture[DependencyModulesResult] = {
    val requestedTargets = params.getTargets.asScala.toSet
    CompletableFuture.completedFuture(
      new DependencyModulesResult(
        importedBuildTargets
          .filter(t => requestedTargets(t.id))
          .map(_.dependencyModulesItem)
          .asJava
      )
    )
  }

  override def buildTargetJvmCompileClasspath(
      params: JvmCompileClasspathParams
  ): CompletableFuture[JvmCompileClasspathResult] = {
    val requestedTargets = params.getTargets.asScala.toSet
    CompletableFuture.completedFuture {
      new JvmCompileClasspathResult(
        importedBuildTargets
          .filter(t => requestedTargets(t.id))
          .map(_.javacOptionsItem(workspace))
          .map { item =>
            new JvmCompileClasspathItem(
              item.getTarget,
              item.getClasspath,
            )
          }
          .asJava
      )
    }
  }

  override def debugSessionStart(
      params: DebugSessionParams
  ): CompletableFuture[DebugSessionAddress] = {
    val result = new CompletableFuture[DebugSessionAddress]()
    debugStarter match {
      case None =>
        result.completeExceptionally(
          new UnsupportedOperationException(
            "MBT build server has no debug session starter configured."
          )
        )
      case Some(starter) =>
        val targetEither = for {
          targetId <- params
            .getTargets()
            .asScala
            .headOption
            .toRight("debugSessionStart: no targets in params")
          target <- importedBuildTargets
            .find(_.id == targetId)
            .toRight(s"debugSessionStart: no MBT target for $targetId")
        } yield target

        val outcome: Either[String, Future[URI]] = targetEither.flatMap {
          target =>
            params.asScalaMainClass() match {
              case Right(mainClass) =>
                Right(starter.start(target, mainClass, workspace))
              case Left(_) =>
                params
                  .asScalaTestSuites()
                  .map(testSuites =>
                    starter.startDebugTest(target, testSuites, workspace)
                  )
            }
        }

        outcome match {
          case Left(error) =>
            result.completeExceptionally(new IllegalArgumentException(error))
          case Right(future) =>
            future.onComplete {
              case Success(uri) =>
                result.complete(new DebugSessionAddress(uri.toString))
              case Failure(ex) =>
                result.completeExceptionally(ex)
            }
        }
    }
    result
  }

  override def buildTargetJvmRunEnvironment(
      params: JvmRunEnvironmentParams
  ): CompletableFuture[JvmRunEnvironmentResult] =
    CompletableFuture.completedFuture(
      new JvmRunEnvironmentResult(List.empty.asJava)
    )

  override def buildTargetJvmTestEnvironment(
      params: JvmTestEnvironmentParams
  ): CompletableFuture[JvmTestEnvironmentResult] =
    CompletableFuture.completedFuture(
      new JvmTestEnvironmentResult(List.empty.asJava)
    )

  override def buildTargetWrappedSources(
      params: scala.build.bsp.WrappedSourcesParams
  ): CompletableFuture[scala.build.bsp.WrappedSourcesResult] =
    CompletableFuture.completedFuture(
      new WrappedSourcesResult(List.empty.asJava)
    )
}

object MbtBuildServer {
  val name = "MBT"

  def details: BspConnectionDetails =
    new BspConnectionDetails(
      name,
      List.empty[String].asJava,
      BuildInfo.metalsVersion,
      BuildInfo.bspVersion,
      List("scala", "java").asJava,
    )

  def isMbtServer(name: String): Boolean =
    name == MbtBuildServer.name

  def newServer(
      workspace: AbsolutePath,
      buildClient: MetalsBuildClient,
      languageClient: ConfiguredLanguageClient,
      config: MetalsServerConfig,
      requestTimeOutNotification: DismissedNotifications#Notification,
      reconnectNotification: DismissedNotifications#Notification,
      bspStatusOpt: Option[ConnectionBspStatus],
      mbtBuild: () => MbtBuild,
      workDoneProgress: WorkDoneProgress,
      scalaVersionSelector: ScalaVersionSelector,
      userConfig: () => UserConfiguration,
      debugStarter: Option[MbtDebugSessionStarter] = None,
  )(implicit
      ec: ExecutionContextExecutorService
  ): Future[BuildServerConnection] = {

    val connectionFactory = new BuildServerConnectionFactory(
      workspace,
      workspace,
      buildClient,
      languageClient,
      requestTimeOutNotification,
      reconnectNotification,
      config,
      userConfig(),
      name,
      bspStatusOpt,
      workDoneProgress = workDoneProgress,
    ) {

      override def connect(): Future[SocketConnection] = Future.successful {
        val clientInput = new PipedInputStream()
        val serverOutput = new PipedOutputStream(clientInput)
        val serverInput = new PipedInputStream()
        val clientOutput = new PipedOutputStream(serverInput)
        val finished = Promise[Unit]()
        def eagerBuild() = {
          val updatedBuild = mbtBuild()
          if (updatedBuild.isEmpty) MbtBuild.fromWorkspace(workspace)
          else updatedBuild
        }
        val server =
          new MbtBuildServer(
            workspace,
            eagerBuild,
            scalaVersionSelector,
            debugStarter,
          )
        val serverLauncher = new Launcher.Builder[BuildClient]()
          .setInput(serverInput)
          .setOutput(serverOutput)
          .setLocalService(server)
          .setRemoteInterface(classOf[BuildClient])
          .setExecutorService(ec)
          .create()
        server.onConnectWithClient(serverLauncher.getRemoteProxy)
        val listening = serverLauncher.startListening()
        Future {
          listening.get()
          ()
        }.onComplete(finished.tryComplete)
        val cancel = Cancelable { () =>
          listening.cancel(false)
          clientOutput.close()
          clientInput.close()
          serverInput.close()
          serverOutput.close()
          finished.trySuccess(())
        }
        SocketConnection(
          name,
          new ClosableOutputStream(clientOutput, s"$name output stream"),
          new QuietInputStream(clientInput, s"$name input stream"),
          List(cancel),
          finished,
        )
      }

    }

    connectionFactory.fromSockets()
  }
}
