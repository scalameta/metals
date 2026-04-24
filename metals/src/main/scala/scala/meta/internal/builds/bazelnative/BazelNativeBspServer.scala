package scala.meta.internal.builds.bazelnative

import java.util.Collections
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

import scala.concurrent.ExecutionContext
import scala.jdk.CollectionConverters._

import scala.meta.internal.metals.MetalsBuildServer
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j._
import com.google.gson.GsonBuilder

import scala.build.bsp.WrappedSourcesParams
import scala.build.bsp.WrappedSourcesResult
import scala.build.bsp.WrappedSourcesItem

/**
 * In-process BSP server that translates BSP requests into Bazel commands
 * and uses aspect-collected metadata for rich IDE support.
 */
class BazelNativeBspServer(
    workspace: AbsolutePath,
    process: BazelNativeProcess,
    besServer: BazelNativeBesServer,
    translator: BazelNativeBepTranslator,
    aspectsManager: BazelNativeAspectsManager,
    targetData: BazelNativeTargetData,
)(implicit ec: ExecutionContext)
    extends MetalsBuildServer
    with BazelNativeBspClient {

  private val executor: Executor = (r: Runnable) => ec.execute(r)

  private val gson = new GsonBuilder().create()

  @volatile private var bazelVersion = "unknown"
  @volatile private var detectedScalaVersion: Option[String] = None
  @volatile private var executionRoot: String = ""
  @volatile private var bazelBinDir: String = ""
  @volatile private var buildClient: Option[ch.epfl.scala.bsp4j.BuildClient] =
    None

  def setClient(client: ch.epfl.scala.bsp4j.BuildClient): Unit =
    buildClient = Some(client)

  // -- BazelNativeBspClient implementation --

  override def onBuildTaskStart(params: TaskStartParams): Unit =
    buildClient.foreach(_.onBuildTaskStart(params))
  override def onBuildTaskFinish(params: TaskFinishParams): Unit =
    buildClient.foreach(_.onBuildTaskFinish(params))
  override def onBuildTaskProgress(params: TaskProgressParams): Unit =
    buildClient.foreach(_.onBuildTaskProgress(params))

  override def onBuildPublishDiagnostics(
      params: PublishDiagnosticsParams
  ): Unit =
    buildClient.foreach(_.onBuildPublishDiagnostics(params))
  override def onBuildShowMessage(params: ShowMessageParams): Unit =
    buildClient.foreach(_.onBuildShowMessage(params))
  override def onBuildLogMessage(params: LogMessageParams): Unit =
    buildClient.foreach(_.onBuildLogMessage(params))

  // -- BSP lifecycle --

  override def buildInitialize(
      params: InitializeBuildParams
  ): CompletableFuture[InitializeBuildResult] = {
    scribe.debug("[BazelNative BSP] Request: buildInitialize")
    CompletableFuture.supplyAsync(
      () => {
        bazelVersion = scala.concurrent.Await.result(
          process.version(),
          scala.concurrent.duration.Duration(30, "s"),
        )

        val infoMap = scala.concurrent.Await.result(
          process.info(),
          scala.concurrent.duration.Duration(30, "s"),
        )
        executionRoot = infoMap.getOrElse("execution_root", "")
        bazelBinDir = infoMap.getOrElse("bazel-bin", "")
        scribe.info(
          s"[BazelNative BSP] execution_root=$executionRoot, bazel-bin=$bazelBinDir"
        )

        val scalaRuleset = aspectsManager.detectScalaRulesetName()
        aspectsManager.materialize(scalaRuleset, bazelVersion)
        scribe.info(
          s"[BazelNative BSP] Aspect materialized, scala ruleset=${scalaRuleset.getOrElse("none")}"
        )

        val capabilities = new BuildServerCapabilities()
        capabilities.setCompileProvider(
          new CompileProvider(List("scala", "java").asJava)
        )
        capabilities.setDependencySourcesProvider(true)
        capabilities.setDependencyModulesProvider(true)
        capabilities.setCanReload(true)
        capabilities.setInverseSourcesProvider(true)
        capabilities.setResourcesProvider(true)

        new InitializeBuildResult(
          "Bazel Native",
          bazelVersion,
          "2.1.0",
          capabilities,
        )
      },
      executor,
    )
  }

  override def onBuildInitialized(): Unit = {
    scribe.debug("[BazelNative BSP] Build initialized")
  }

  override def buildShutdown(): CompletableFuture[Object] = {
    scribe.debug("[BazelNative BSP] Request: buildShutdown")
    CompletableFuture.supplyAsync(
      () => {
        besServer.shutdown()
        process.shutdown()
        null: Object
      },
      executor,
    )
  }

  override def onBuildExit(): Unit =
    scribe.debug("[BazelNative BSP] onBuildExit")

  // -- Workspace targets --

  override def workspaceBuildTargets()
      : CompletableFuture[WorkspaceBuildTargetsResult] = {
    scribe.debug("[BazelNative BSP] Request: workspaceBuildTargets")
    CompletableFuture.supplyAsync(
      () => {
        runInitialSync()

        if (detectedScalaVersion.isEmpty)
          detectedScalaVersion = deriveScalaVersion()

        val targets = targetData.allTargets.values
          .filter(info => isUserTarget(info.id))
          .map(info => buildTarget(info))
          .toList

        scribe.info(
          s"[BazelNative BSP] Returning ${targets.size} build targets"
        )
        new WorkspaceBuildTargetsResult(targets.asJava)
      },
      executor,
    )
  }

  override def workspaceReload(): CompletableFuture[Object] = {
    scribe.debug("[BazelNative BSP] Request: workspaceReload")
    CompletableFuture.supplyAsync(
      () => {
        targetData.clear()
        buildClient.foreach { client =>
          client.onBuildTargetDidChange(
            new DidChangeBuildTarget(Collections.emptyList())
          )
        }
        null: Object
      },
      executor,
    )
  }

  // -- Compile --

  override def buildTargetCompile(
      params: CompileParams
  ): CompletableFuture[CompileResult] = {
    val targets = params.getTargets.asScala.toList

    if (targets.isEmpty)
      return CompletableFuture.completedFuture(
        new CompileResult(StatusCode.OK)
      )

    CompletableFuture.supplyAsync(
      () => {
        val originId =
          Option(params.getOriginId).getOrElse(UUID.randomUUID().toString)
        translator.setOriginId(originId)
        translator.setTargets(targets)
        translator.notifyBuildStarted(originId)

        val labels = targets.map(extractLabel)
        val besPort = besServer.port

        var exitCode = 99
        try {
          scribe.debug(
            s"[BazelNative BSP] Launching Bazel build, targets=${targets}"
          )
          exitCode = scala.concurrent.Await.result(
            process.build(
              labels,
              besPort,
              extraFlags = aspectsManager.extraCompileFlags,
              onStderr = translator.onBuildStderr,
            ),
            scala.concurrent.duration.Duration(10, "min"),
          )
        } catch {
          case e: Exception =>
            scribe.error(s"[BazelNative BSP] Build failed: ${e.getMessage}")
        } finally {
          scribe.debug(
            s"[BazelNative BSP] Build exited with code ${exitCode} for targets=${targets}"
          )
          translator.notifyBuildFinished(originId, exitCode)
          translator.clearState()
        }

        refreshTargetData()

        val statusCode =
          if (exitCode == 0) StatusCode.OK else StatusCode.ERROR
        val result = new CompileResult(statusCode)
        result.setOriginId(originId)
        result
      },
      executor,
    )
  }

  // -- Sources --

  override def buildTargetSources(
      params: SourcesParams
  ): CompletableFuture[SourcesResult] = {
    scribe.debug("[BazelNative BSP] Request: buildTargetSources")
    CompletableFuture.supplyAsync(
      () => {
        val items = params.getTargets.asScala.map { targetId =>
          val label = extractLabel(targetId)
          val info = targetData.get(label)
          val sources = info
            .map { i =>
              val regular = i.sources.map { fl =>
                new SourceItem(resolveSourceUri(fl), SourceItemKind.FILE, false)
              }
              val generated = i.generatedSources.map { fl =>
                new SourceItem(resolveOutputUri(fl), SourceItemKind.FILE, true)
              }
              regular ++ generated
            }
            .getOrElse(Nil)
          new SourcesItem(targetId, sources.asJava)
        }
        new SourcesResult(items.asJava)
      },
      executor,
    )
  }

  // -- Resources --

  override def buildTargetResources(
      params: ResourcesParams
  ): CompletableFuture[ResourcesResult] = {
    scribe.debug("[BazelNative BSP] Request: buildTargetResources")
    CompletableFuture.supplyAsync(
      () => {
        val items = params.getTargets.asScala.map { targetId =>
          val info = targetData.get(extractLabel(targetId))
          val uris = info
            .map(_.resources.map(fl => resolveSourceUri(fl)))
            .getOrElse(Nil)
          new ResourcesItem(targetId, uris.asJava)
        }
        new ResourcesResult(items.asJava)
      },
      executor,
    )
  }

  // -- Dependency sources --

  override def buildTargetDependencySources(
      params: DependencySourcesParams
  ): CompletableFuture[DependencySourcesResult] = {
    scribe.debug("[BazelNative BSP] Request: buildTargetDependencySources")
    CompletableFuture.supplyAsync(
      () => {
        val items = params.getTargets.asScala.map { targetId =>
          val info = targetData.get(extractLabel(targetId))
          val sourceJarUris = info
            .flatMap(_.jvmTargetInfo)
            .map { jvm =>
              jvm.jars.flatMap(_.sourceJars.map(resolveOutputUri)) ++
                jvm.generatedJars.flatMap(_.sourceJars.map(resolveOutputUri))
            }
            .getOrElse(Nil)
            .distinct
          new DependencySourcesItem(targetId, sourceJarUris.asJava)
        }
        new DependencySourcesResult(items.asJava)
      },
      executor,
    )
  }

  // -- Inverse sources --

  override def buildTargetInverseSources(
      params: InverseSourcesParams
  ): CompletableFuture[InverseSourcesResult] = {
    scribe.debug("[BazelNative BSP] Request: buildTargetInverseSources")
    CompletableFuture.supplyAsync(
      () => {
        val docUri = params.getTextDocument.getUri
        val matching = targetData.allTargets.collect {
          case (label, info)
              if info.sources.exists(fl => resolveSourceUri(fl) == docUri) =>
            labelToTargetId(label)
        }.toList
        new InverseSourcesResult(matching.asJava)
      },
      executor,
    )
  }

  // -- Clean --

  override def buildTargetCleanCache(
      params: CleanCacheParams
  ): CompletableFuture[CleanCacheResult] = {
    scribe.debug("[BazelNative BSP] Request: buildTargetCleanCache")
    CompletableFuture.completedFuture(new CleanCacheResult(false))
  }

  // -- Run / Test --

  override def buildTargetRun(
      params: RunParams
  ): CompletableFuture[RunResult] = {
    scribe.debug("[BazelNative BSP] Request: buildTargetRun")
    CompletableFuture.completedFuture(new RunResult(StatusCode.OK))
  }

  override def buildTargetTest(
      params: TestParams
  ): CompletableFuture[TestResult] = {
    scribe.debug("[BazelNative BSP] Request: buildTargetTest")
    CompletableFuture.completedFuture(new TestResult(StatusCode.OK))
  }

  override def debugSessionStart(
      params: DebugSessionParams
  ): CompletableFuture[DebugSessionAddress] = {
    val f = new CompletableFuture[DebugSessionAddress]()
    f.completeExceptionally(
      new UnsupportedOperationException(
        "Bazel Native does not support debugging yet"
      )
    )
    f
  }

  // -- Scala BSP --

  override def buildTargetScalacOptions(
      params: ScalacOptionsParams
  ): CompletableFuture[ScalacOptionsResult] = {
    scribe.debug("[BazelNative BSP] Request: buildTargetScalacOptions")
    CompletableFuture.completedFuture(
      new ScalacOptionsResult(
        params.getTargets.asScala.map { id =>
          val label = extractLabel(id)
          val info = targetData.get(label)

          val rawOpts = info
            .flatMap(_.scalaTargetInfo)
            .map(_.scalacOpts)
            .getOrElse(Nil)

          val opts = withSemanticdbTargetroot(rawOpts, label)

          val cp = info
            .flatMap(_.jvmTargetInfo)
            .map(classpathUris)
            .getOrElse(Nil)

          val classDir = info
            .flatMap(_.jvmTargetInfo)
            .flatMap(_.jars.headOption)
            .flatMap(_.binaryJars.headOption)
            .map(fl => resolveOutputParentUri(fl))
            .getOrElse(fallbackClassDir(label))

          new ScalacOptionsItem(id, opts.asJava, cp.asJava, classDir)
        }.asJava
      )
    )
  }

  override def buildTargetScalaMainClasses(
      params: ScalaMainClassesParams
  ): CompletableFuture[ScalaMainClassesResult] = {
    scribe.debug("[BazelNative BSP] Request: buildTargetScalaMainClasses")
    CompletableFuture.supplyAsync(
      () => {
        val items = params.getTargets.asScala.map { id =>
          val info = targetData.get(extractLabel(id))
          val mainClasses = info
            .flatMap(_.jvmTargetInfo)
            .filter(_.mainClass.nonEmpty)
            .map { jvm =>
              val mc = new ScalaMainClass(
                jvm.mainClass,
                jvm.args.asJava,
                jvm.jvmFlags.asJava,
              )
              List(mc)
            }
            .getOrElse(Nil)
          new ScalaMainClassesItem(id, mainClasses.asJava)
        }
        new ScalaMainClassesResult(items.asJava)
      },
      executor,
    )
  }

  override def buildTargetScalaTestClasses(
      params: ScalaTestClassesParams
  ): CompletableFuture[ScalaTestClassesResult] = {
    scribe.debug("[BazelNative BSP] Request: buildTargetScalaTestClasses")
    CompletableFuture.completedFuture(
      new ScalaTestClassesResult(Collections.emptyList())
    )
  }

  // -- Java BSP --

  override def buildTargetJavacOptions(
      params: JavacOptionsParams
  ): CompletableFuture[JavacOptionsResult] = {
    scribe.debug("[BazelNative BSP] Request: buildTargetJavacOptions")
    CompletableFuture.completedFuture(
      new JavacOptionsResult(
        params.getTargets.asScala.map { id =>
          val label = extractLabel(id)
          val info = targetData.get(label)

          val opts = info
            .flatMap(_.jvmTargetInfo)
            .map(_.javacOpts)
            .getOrElse(Nil)

          val cp = info
            .flatMap(_.jvmTargetInfo)
            .map(classpathUris)
            .getOrElse(Nil)

          val classDir = info
            .flatMap(_.jvmTargetInfo)
            .flatMap(_.jars.headOption)
            .flatMap(_.binaryJars.headOption)
            .map(fl => resolveOutputParentUri(fl))
            .getOrElse(fallbackClassDir(label))

          new JavacOptionsItem(id, opts.asJava, cp.asJava, classDir)
        }.asJava
      )
    )
  }

  // -- JVM BSP --

  override def buildTargetJvmRunEnvironment(
      params: JvmRunEnvironmentParams
  ): CompletableFuture[JvmRunEnvironmentResult] = {
    scribe.debug("[BazelNative BSP] Request: buildTargetJvmRunEnvironment")
    CompletableFuture.supplyAsync(
      () => {
        val items = params.getTargets.asScala.map { id =>
          jvmEnvironmentItem(id)
        }
        new JvmRunEnvironmentResult(items.asJava)
      },
      executor,
    )
  }

  override def buildTargetJvmTestEnvironment(
      params: JvmTestEnvironmentParams
  ): CompletableFuture[JvmTestEnvironmentResult] = {
    scribe.debug("[BazelNative BSP] Request: buildTargetJvmTestEnvironment")
    CompletableFuture.supplyAsync(
      () => {
        val items = params.getTargets.asScala.map { id =>
          jvmEnvironmentItem(id)
        }
        new JvmTestEnvironmentResult(items.asJava)
      },
      executor,
    )
  }

  override def buildTargetJvmCompileClasspath(
      params: JvmCompileClasspathParams
  ): CompletableFuture[JvmCompileClasspathResult] = {
    scribe.debug("[BazelNative BSP] Request: buildTargetJvmCompileClasspath")
    CompletableFuture.supplyAsync(
      () => {
        val items = params.getTargets.asScala.map { id =>
          val info = targetData.get(extractLabel(id))
          val cp = info
            .flatMap(_.jvmTargetInfo)
            .map(_.transitiveCompileTimeJars.map(resolveOutputUri))
            .getOrElse(Nil)
          new JvmCompileClasspathItem(id, cp.asJava)
        }

        new JvmCompileClasspathResult(items.asJava)
      },
      executor,
    )
  }

  // -- Dependency modules --

  override def buildTargetDependencyModules(
      params: DependencyModulesParams
  ): CompletableFuture[DependencyModulesResult] = {
    scribe.debug("[BazelNative BSP] Request: buildTargetDependencyModules")
    CompletableFuture.supplyAsync(
      () => {
        val items = params.getTargets.asScala.map { targetId =>
          val info = targetData.get(extractLabel(targetId))
          val modules = info
            .flatMap(_.jvmTargetInfo)
            .map { jvm =>
              (jvm.jars ++ jvm.generatedJars).flatMap { outputs =>
                outputs.binaryJars.flatMap(buildDependencyModule)
              }
            }
            .getOrElse(Nil)
          new DependencyModulesItem(targetId, modules.asJava)
        }
        new DependencyModulesResult(items.asJava)
      },
      executor,
    )
  }

  // -- Output paths --

  override def buildTargetOutputPaths(
      params: OutputPathsParams
  ): CompletableFuture[OutputPathsResult] = {
    scribe.debug("[BazelNative BSP] Request: buildTargetOutputPaths")
    CompletableFuture.completedFuture(
      new OutputPathsResult(Collections.emptyList())
    )
  }

  override def onRunReadStdin(params: ReadParams): Unit = ()

  override def buildTargetWrappedSources(
      params: WrappedSourcesParams
  ): CompletableFuture[WrappedSourcesResult] =
    CompletableFuture.completedFuture(
      new WrappedSourcesResult(Collections.emptyList[WrappedSourcesItem]())
    )

  // ---------------------------------------------------------------------------
  // Internal helpers
  // ---------------------------------------------------------------------------

  private def runInitialSync(): Unit = {
    if (!targetData.isEmpty) return
    try {
      scribe.info("[BazelNative BSP] Running initial aspect sync...")
      val exitCode = scala.concurrent.Await.result(
        process.syncBuild(aspectsManager.extraSyncFlags),
        scala.concurrent.duration.Duration(5, "min"),
      )
      scribe.info(s"[BazelNative BSP] Sync completed (exit=$exitCode)")
      refreshTargetData()
    } catch {
      case e: Exception =>
        scribe.warn(
          s"[BazelNative BSP] Initial sync failed: ${e.getMessage}"
        )
    }
  }

  private def refreshTargetData(): Unit = {
    if (bazelBinDir.isEmpty) return
    val raw = BazelNativeTargetInfoReader.readFromBazelBin(
      java.nio.file.Paths.get(bazelBinDir)
    )
    val data = raw.map { case (label, info) =>
      val normalized = normalizeLabel(label)
      val normalizedDeps =
        info.dependencies.map(d => d.copy(id = normalizeLabel(d.id)))
      normalized -> info.copy(id = normalized, dependencies = normalizedDeps)
    }
    targetData.update(data)
    scribe.info(
      s"[BazelNative BSP] Loaded aspect data for ${data.size} targets"
    )
  }

  /** Strip `@` / `@@` main-repo prefix so labels are always `//pkg:name`. */
  private def normalizeLabel(label: String): String = {
    val stripped = label.replaceFirst("^@+", "")
    if (stripped.startsWith("//")) stripped
    else label
  }

  private def isUserTarget(label: String): Boolean =
    label.startsWith("//") &&
      !label.startsWith("//.bazelbsp/") &&
      !label.startsWith("//.metals-bsp/") &&
      !label.contains("toolchain_impl")

  private def buildTarget(info: BspTargetInfo): BuildTarget = {
    val id = labelToTargetId(info.id)
    val deps = info.dependencies.map(d => labelToTargetId(d.id)).asJava
    val langs = List("scala", "java").asJava

    val caps = new BuildTargetCapabilities()
    caps.setCanCompile(true)
    val kind = info.kind
    caps.setCanRun(
      info.executable ||
        kind.endsWith("_binary")
    )
    caps.setCanTest(kind.endsWith("_test"))

    val target = new BuildTarget(id, info.tags.asJava, langs, deps, caps)
    target.setDisplayName(info.id)
    target.setBaseDirectory(workspace.toURI.toString)

    info.scalaTargetInfo.foreach { sti =>
      val sv = deriveScalaVersionFromClasspath(sti.compilerClasspath)
        .orElse(detectedScalaVersion)
        .getOrElse("2.13.12")
      val scalaBt = new ScalaBuildTarget(
        "org.scala-lang",
        sv,
        scalaBinaryVersion(sv),
        ScalaPlatform.JVM,
        sti.compilerClasspath.map(resolveOutputUri).asJava,
      )
      info.javaToolchainInfo.foreach { jtc =>
        val jvmBt = new JvmBuildTarget()
        if (jtc.javaHome.isDefined)
          jvmBt.setJavaHome(resolveOutputUri(jtc.javaHome.get))
        jvmBt.setJavaVersion(jtc.sourceVersion)
        scalaBt.setJvmBuildTarget(jvmBt)
      }
      target.setDataKind(BuildTargetDataKind.SCALA)
      target.setData(gson.toJsonTree(scalaBt))
    }

    if (info.scalaTargetInfo.isEmpty) {
      info.javaToolchainInfo.foreach { jtc =>
        val jvmBt = new JvmBuildTarget()
        if (jtc.javaHome.isDefined)
          jvmBt.setJavaHome(resolveOutputUri(jtc.javaHome.get))
        jvmBt.setJavaVersion(jtc.sourceVersion)
        target.setDataKind("jvm")
        target.setData(gson.toJsonTree(jvmBt))
      }
    }

    target
  }

  private def jvmEnvironmentItem(
      id: BuildTargetIdentifier
  ): JvmEnvironmentItem = {
    val info = targetData.get(extractLabel(id))
    val jvm = info.flatMap(_.jvmTargetInfo)
    val cp = jvm.map(classpathUris).getOrElse(Nil)
    val jvmFlags = jvm.map(_.jvmFlags).getOrElse(Nil)
    val envVars = info.map(_.env).getOrElse(Map.empty)
    val envMap = new java.util.HashMap[String, String]()
    envVars.foreach { case (k, v) => envMap.put(k, v) }
    info.foreach { i =>
      i.envInherit.foreach { varName =>
        val value = System.getenv(varName)
        if (value != null) envMap.put(varName, value)
      }
    }

    new JvmEnvironmentItem(
      id,
      cp.asJava,
      jvmFlags.asJava,
      workspace.toString,
      envMap,
    )
  }

  private def classpathUris(jvm: BspJvmTargetInfo): List[String] = {
    val jarUris = jvm.jars.flatMap { outputs =>
      outputs.binaryJars.map(resolveOutputUri) ++
        outputs.interfaceJars.map(resolveOutputUri)
    }
    val transitiveUris =
      jvm.transitiveCompileTimeJars.map(resolveOutputUri)
    (jarUris ++ transitiveUris).distinct
  }

  private def buildDependencyModule(
      fl: BspFileLocation
  ): Option[DependencyModule] = {
    try {
      val uri = resolveOutputUri(fl)
      val name = java.nio.file.Paths
        .get(fl.path)
        .getFileName
        .toString
        .stripSuffix(".jar")
      val artifacts = new java.util.ArrayList[MavenDependencyModuleArtifact]()
      artifacts.add(new MavenDependencyModuleArtifact(uri))
      val mavenModule = new MavenDependencyModule("", name, "", artifacts)
      val depModule = new DependencyModule(name, "")
      depModule.setDataKind(DependencyModuleDataKind.MAVEN)
      depModule.setData(gson.toJsonTree(mavenModule))
      Some(depModule)
    } catch {
      case _: Exception => None
    }
  }

  private def resolveSourceUri(fl: BspFileLocation): String = {
    val resolved =
      if (fl.path.startsWith("/")) java.nio.file.Paths.get(fl.path)
      else workspace.resolve(fl.path).toNIO
    resolved.toUri.toString
  }

  private def resolveOutputUri(fl: BspFileLocation): String = {
    if (fl.isSource) return resolveSourceUri(fl)
    val resolved =
      if (fl.path.startsWith("/")) java.nio.file.Paths.get(fl.path)
      else if (executionRoot.nonEmpty)
        java.nio.file.Paths.get(executionRoot).resolve(fl.path)
      else workspace.resolve(fl.path).toNIO
    resolved.toUri.toString
  }

  private def resolveOutputParentUri(fl: BspFileLocation): String = {
    val resolved =
      if (fl.path.startsWith("/")) java.nio.file.Paths.get(fl.path)
      else if (executionRoot.nonEmpty)
        java.nio.file.Paths.get(executionRoot).resolve(fl.path)
      else workspace.resolve(fl.path).toNIO
    resolved.getParent.toUri.toString
  }

  /**
   * rules_scala with `enable_semanticdb = True` produces .semanticdb
   * files under `bazel-bin/<pkg>/_semanticdb/<name>/` but does not
   * expose that path through scalac options.  Inject the flag so
   * Metals can locate them.
   */
  private def withSemanticdbTargetroot(
      opts: List[String],
      label: String,
  ): List[String] = {
    if (
      bazelBinDir.isEmpty ||
      opts.exists(_.startsWith("-P:semanticdb:targetroot:"))
    )
      return opts

    val idx = label.indexOf(':')
    if (idx < 0) return opts
    val pkg = label.substring(0, idx).stripPrefix("//")
    val name = label.substring(idx + 1)

    val binPath = java.nio.file.Paths.get(bazelBinDir)
    val sdbPath =
      if (pkg.nonEmpty)
        binPath.resolve(pkg).resolve("_semanticdb").resolve(name)
      else binPath.resolve("_semanticdb").resolve(name)
    opts :+ s"-P:semanticdb:targetroot:$sdbPath"
  }

  @SuppressWarnings(Array("unused"))
  private def fallbackClassDir(@annotation.unused label: String): String =
    if (bazelBinDir.nonEmpty)
      java.nio.file.Paths.get(bazelBinDir).toUri.toString
    else workspace.resolve("bazel-bin").toURI.toString

  private def deriveScalaVersion(): Option[String] =
    targetData.allTargets.values
      .flatMap(_.scalaTargetInfo)
      .flatMap(sti => deriveScalaVersionFromClasspath(sti.compilerClasspath))
      .headOption
      .orElse(detectScalaVersionFromFiles())

  private def deriveScalaVersionFromClasspath(
      cp: List[BspFileLocation]
  ): Option[String] = {
    val VersionPattern = """scala-library-(\d+\.\d+\.\d+)\.jar""".r
    val Scala3Pattern = """scala3-library_3-(\d+\.\d+\.\d+)\.jar""".r
    cp.map(_.path)
      .collectFirst {
        case path if path.contains("scala3-library") =>
          Scala3Pattern.findFirstMatchIn(path).map(_.group(1))
        case path if path.contains("scala-library") =>
          VersionPattern.findFirstMatchIn(path).map(_.group(1))
      }
      .flatten
  }

  private def detectScalaVersionFromFiles(): Option[String] = {
    val ScalaVersionPattern =
      """scala_(?:config|version)\s*(?:\(scala_version\s*=\s*|=\s*)"([^"]+)"""".r
    List("WORKSPACE", "WORKSPACE.bazel", "MODULE.bazel").iterator
      .map(workspace.resolve)
      .filter(_.isFile)
      .flatMap { file =>
        val content = new String(
          java.nio.file.Files.readAllBytes(file.toNIO),
          java.nio.charset.StandardCharsets.UTF_8,
        )
        ScalaVersionPattern.findFirstMatchIn(content).map(_.group(1))
      }
      .nextOption()
  }

  private def scalaBinaryVersion(sv: String): String =
    if (sv.startsWith("3.")) "3"
    else sv.split('.').take(2).mkString(".")

  private def labelToTargetId(label: String): BuildTargetIdentifier =
    new BuildTargetIdentifier(s"${workspace.toURI}?$label")

  private def extractLabel(id: BuildTargetIdentifier): String = {
    val uri = id.getUri
    val idx = uri.indexOf('?')
    if (idx >= 0) uri.substring(idx + 1) else uri
  }
}
