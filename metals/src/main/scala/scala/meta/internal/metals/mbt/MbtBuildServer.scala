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
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.jdk.CollectionConverters._

import scala.meta.internal.bsp.ConnectionBspStatus
import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.BuildServerConnection
import scala.meta.internal.metals.Cancelable
import scala.meta.internal.metals.ClosableOutputStream
import scala.meta.internal.metals.DismissedNotifications
import scala.meta.internal.metals.MetalsBuildClient
import scala.meta.internal.metals.MetalsBuildServer
import scala.meta.internal.metals.MetalsEnrichments.XtensionAbsolutePathBuffers
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.QuietInputStream
import scala.meta.internal.metals.ScalaVersionSelector
import scala.meta.internal.metals.SocketConnection
import scala.meta.internal.metals.WorkDoneProgress
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BspConnectionDetails
import ch.epfl.scala.bsp4j.BuildClient
import ch.epfl.scala.bsp4j.BuildServerCapabilities
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.CleanCacheParams
import ch.epfl.scala.bsp4j.CleanCacheResult
import ch.epfl.scala.bsp4j.CompileParams
import ch.epfl.scala.bsp4j.CompileProvider
import ch.epfl.scala.bsp4j.CompileResult
import ch.epfl.scala.bsp4j.DebugSessionAddress
import ch.epfl.scala.bsp4j.DebugSessionParams
import ch.epfl.scala.bsp4j.DependencyModulesParams
import ch.epfl.scala.bsp4j.DependencyModulesResult
import ch.epfl.scala.bsp4j.DependencySourcesParams
import ch.epfl.scala.bsp4j.DependencySourcesResult
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
import ch.epfl.scala.bsp4j.ReadParams
import ch.epfl.scala.bsp4j.ResourcesParams
import ch.epfl.scala.bsp4j.ResourcesResult
import ch.epfl.scala.bsp4j.RunParams
import ch.epfl.scala.bsp4j.RunResult
import ch.epfl.scala.bsp4j.ScalaMainClassesParams
import ch.epfl.scala.bsp4j.ScalaMainClassesResult
import ch.epfl.scala.bsp4j.ScalaTestClassesParams
import ch.epfl.scala.bsp4j.ScalaTestClassesResult
import ch.epfl.scala.bsp4j.ScalacOptionsParams
import ch.epfl.scala.bsp4j.ScalacOptionsResult
import ch.epfl.scala.bsp4j.SourcesParams
import ch.epfl.scala.bsp4j.SourcesResult
import ch.epfl.scala.bsp4j.StatusCode
import ch.epfl.scala.bsp4j.TestParams
import ch.epfl.scala.bsp4j.TestResult
import ch.epfl.scala.bsp4j.WorkspaceBuildTargetsResult
import org.eclipse.lsp4j.jsonrpc.Launcher

final class MbtBuildServer(
    workspace: AbsolutePath,
    build: () => MbtBuild,
    scalaVersionSelector: ScalaVersionSelector,
) extends MetalsBuildServer {

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
    importedBuild.set(build().mbtTargets)
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
  ): CompletableFuture[CompileResult] =
    CompletableFuture.completedFuture(new CompileResult(StatusCode.OK))

  override def buildTargetTest(
      params: TestParams
  ): CompletableFuture[TestResult] =
    CompletableFuture.failedFuture(
      new UnsupportedOperationException(
        "MBT build server does not support 'buildTarget/test'."
      )
    )

  override def buildTargetRun(
      params: RunParams
  ): CompletableFuture[RunResult] =
    CompletableFuture.failedFuture(
      new UnsupportedOperationException(
        "MBT build server does not support 'buildTarget/run'."
      )
    )

  override def buildTargetCleanCache(
      params: CleanCacheParams
  ): CompletableFuture[CleanCacheResult] =
    CompletableFuture.completedFuture {
      val result = new CleanCacheResult(false)
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
  ): CompletableFuture[DebugSessionAddress] =
    CompletableFuture.failedFuture(
      new UnsupportedOperationException(
        "MBT build server does not support debug sessions."
      )
    )

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
      languageClient: MetalsLanguageClient,
      config: MetalsServerConfig,
      requestTimeOutNotification: DismissedNotifications#Notification,
      reconnectNotification: DismissedNotifications#Notification,
      bspStatusOpt: Option[ConnectionBspStatus],
      mbtBuild: () => MbtBuild,
      workDoneProgress: WorkDoneProgress,
      scalaVersionSelector: ScalaVersionSelector,
  )(implicit
      ec: ExecutionContextExecutorService
  ): Future[BuildServerConnection] = {
    def connect(): Future[SocketConnection] = Future.successful {
      val clientInput = new PipedInputStream()
      val serverOutput = new PipedOutputStream(clientInput)
      val serverInput = new PipedInputStream()
      val clientOutput = new PipedOutputStream(serverInput)
      val finished = Promise[Unit]()
      val server = new MbtBuildServer(workspace, mbtBuild, scalaVersionSelector)
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

    BuildServerConnection.fromSockets(
      workspace,
      workspace.resolve(".metals").resolve("bsp.trace"),
      buildClient,
      languageClient,
      connect,
      requestTimeOutNotification,
      reconnectNotification,
      config,
      name,
      bspStatusOpt,
      workDoneProgress = workDoneProgress,
    )
  }
}
