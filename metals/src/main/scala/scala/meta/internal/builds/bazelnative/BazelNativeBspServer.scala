package scala.meta.internal.builds.bazelnative

import java.util.Collections
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

import scala.build.bsp.WrappedSourcesItem
import scala.build.bsp.WrappedSourcesParams
import scala.build.bsp.WrappedSourcesResult
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

import scala.meta.internal.metals.MetalsBuildServer
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j._
import com.google.gson.GsonBuilder

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

  private def async[T](f: => T): CompletableFuture[T] =
    CompletableFuture.supplyAsync(() => f, executor)

  private val gson = new GsonBuilder().create()

  @volatile private var bazelVersion = "unknown"
  @volatile private var detectedScalaVersion: Option[String] = None
  @volatile private var executionRoot: String = ""
  @volatile private var bazelBinDir: String = ""
  @volatile private var buildClient: Option[ch.epfl.scala.bsp4j.BuildClient] =
    None
  private val initialSyncDone =
    new java.util.concurrent.atomic.AtomicBoolean(false)

  def setClient(client: ch.epfl.scala.bsp4j.BuildClient): Unit =
    buildClient = Some(client)

  override def onBuildTaskStart(params: TaskStartParams): Unit =
    buildClient.foreach(_.onBuildTaskStart(params))
  override def onBuildTaskFinish(params: TaskFinishParams): Unit =
    buildClient.foreach(_.onBuildTaskFinish(params))
  override def onBuildTaskProgress(params: TaskProgressParams): Unit =
    buildClient.foreach(_.onBuildTaskProgress(params))

  override def buildInitialize(
      params: InitializeBuildParams
  ): CompletableFuture[InitializeBuildResult] = {
    val initTimeout: FiniteDuration = Duration(30, SECONDS)
    async {
      val (ver, infoMap) = scala.concurrent.Await.result(
        process.version().zip(process.info()),
        initTimeout,
      )
      bazelVersion = ver
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
      capabilities.setRunProvider(
        new RunProvider(Collections.emptyList())
      )
      capabilities.setTestProvider(
        new TestProvider(Collections.emptyList())
      )
      capabilities.setDependencySourcesProvider(true)
      capabilities.setDependencyModulesProvider(true)
      capabilities.setCanReload(true)
      capabilities.setInverseSourcesProvider(true)
      capabilities.setResourcesProvider(true)
      capabilities.setDebugProvider(
        new DebugProvider(Collections.emptyList())
      )

      new InitializeBuildResult(
        "Bazel Native",
        bazelVersion,
        "2.1.0",
        capabilities,
      )
    }
  }

  override def onBuildInitialized(): Unit = ()

  override def buildShutdown(): CompletableFuture[Object] = {
    async {
      besServer.shutdown()
      process.shutdown()
      null: Object
    }
  }

  override def onBuildExit(): Unit = ()

  override def workspaceBuildTargets()
      : CompletableFuture[WorkspaceBuildTargetsResult] = {
    async {
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
    }
  }

  override def workspaceReload(): CompletableFuture[Object] = {
    async {
      targetData.clear()
      refreshTargetData()
      buildClient.foreach { client =>
        client.onBuildTargetDidChange(
          new DidChangeBuildTarget(Collections.emptyList())
        )
      }
      null: Object
    }
  }

  override def buildTargetCompile(
      params: CompileParams
  ): CompletableFuture[CompileResult] = {
    val targets = params.getTargets.asScala.toList
    scribe.info(
      s"[BazelNative BSP] Request: buildTargetCompile, targets=${targets.size}"
    )

    if (targets.isEmpty)
      return CompletableFuture.completedFuture(
        new CompileResult(StatusCode.OK)
      )

    async {
      val originId =
        Option(params.getOriginId).getOrElse(UUID.randomUUID().toString)
      translator.setOriginId(originId)
      translator.setTargets(targets)
      translator.notifyBuildStarted(originId)

      val labels = targets.map(extractLabel)
      val besPort = besServer.port

      val exitCode = scala.concurrent.Await.result(
        process.build(
          labels,
          besPort,
          extraFlags = aspectsManager.extraCompileFlags,
          onStderr = translator.onBuildStderr,
        ),
        scala.concurrent.duration.Duration(10, "min"),
      )
      translator.notifyBuildFinished(originId, exitCode)
      translator.clearState()

      refreshTargetData()

      val statusCode =
        if (exitCode == 0) StatusCode.OK else StatusCode.ERROR
      val result = new CompileResult(statusCode)
      result.setOriginId(originId)
      result
    }
  }

  override def buildTargetSources(
      params: SourcesParams
  ): CompletableFuture[SourcesResult] = {
    async {
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
    }
  }

  override def buildTargetResources(
      params: ResourcesParams
  ): CompletableFuture[ResourcesResult] = {
    async {
      val items = params.getTargets.asScala.map { targetId =>
        val info = targetData.get(extractLabel(targetId))
        val uris = info
          .map(_.resources.map(fl => resolveSourceUri(fl)))
          .getOrElse(Nil)
        new ResourcesItem(targetId, uris.asJava)
      }
      new ResourcesResult(items.asJava)
    }
  }

  override def buildTargetDependencySources(
      params: DependencySourcesParams
  ): CompletableFuture[DependencySourcesResult] = {
    async {
      val items = params.getTargets.asScala.map { targetId =>
        val info = targetData.get(extractLabel(targetId))
        val sourceJarUris = info
          .flatMap(_.jvmTargetInfo)
          .map { jvm =>
            jvm.jars.flatMap(_.sourceJars.map(resolveOutputUri)) ++
              jvm.generatedJars.flatMap(_.sourceJars.map(resolveOutputUri)) ++
              jvm.transitiveSourceJars.map(resolveOutputUri)
          }
          .getOrElse(Nil)
          .distinct
        new DependencySourcesItem(targetId, sourceJarUris.asJava)
      }
      new DependencySourcesResult(items.asJava)
    }
  }

  override def buildTargetInverseSources(
      params: InverseSourcesParams
  ): CompletableFuture[InverseSourcesResult] = {
    async {
      val docUri = params.getTextDocument.getUri
      val matching = targetData.allTargets.collect {
        case (label, info)
            if info.sources.exists(fl => resolveSourceUri(fl) == docUri) =>
          labelToTargetId(label)
      }.toList
      new InverseSourcesResult(matching.asJava)
    }
  }

  override def buildTargetCleanCache(
      params: CleanCacheParams
  ): CompletableFuture[CleanCacheResult] = {
    async {
      if (bazelBinDir.nonEmpty) {
        val ok = BazelNativeTargetInfoReader.deleteAspectInfoFiles(
          java.nio.file.Paths.get(bazelBinDir)
        )
        if (ok) targetData.clear()
        new CleanCacheResult(ok)
      } else new CleanCacheResult(true)
    }
  }

  override def buildTargetRun(
      params: RunParams
  ): CompletableFuture[RunResult] = {
    val f = new CompletableFuture[RunResult]()
    f.completeExceptionally(
      new UnsupportedOperationException(
        "Bazel Native does not support run via BSP yet"
      )
    )
    f
  }

  override def buildTargetTest(
      params: TestParams
  ): CompletableFuture[TestResult] = {
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

  override def buildTargetScalacOptions(
      params: ScalacOptionsParams
  ): CompletableFuture[ScalacOptionsResult] = {
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
            .getOrElse(fallbackClassDir)

          new ScalacOptionsItem(id, opts.asJava, cp.asJava, classDir)
        }.asJava
      )
    )
  }

  override def buildTargetScalaMainClasses(
      params: ScalaMainClassesParams
  ): CompletableFuture[ScalaMainClassesResult] = {
    async {
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
    }
  }

  override def buildTargetScalaTestClasses(
      params: ScalaTestClassesParams
  ): CompletableFuture[ScalaTestClassesResult] = {
    CompletableFuture.completedFuture(
      new ScalaTestClassesResult(Collections.emptyList())
    )
  }

  override def buildTargetJavacOptions(
      params: JavacOptionsParams
  ): CompletableFuture[JavacOptionsResult] = {
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
            .getOrElse(fallbackClassDir)

          new JavacOptionsItem(id, opts.asJava, cp.asJava, classDir)
        }.asJava
      )
    )
  }

  override def buildTargetJvmRunEnvironment(
      params: JvmRunEnvironmentParams
  ): CompletableFuture[JvmRunEnvironmentResult] = {
    async {
      val items = params.getTargets.asScala.map { id =>
        jvmEnvironmentItem(id)
      }
      new JvmRunEnvironmentResult(items.asJava)
    }
  }

  override def buildTargetJvmTestEnvironment(
      params: JvmTestEnvironmentParams
  ): CompletableFuture[JvmTestEnvironmentResult] = {
    async {
      val items = params.getTargets.asScala.map { id =>
        jvmEnvironmentItem(id)
      }
      new JvmTestEnvironmentResult(items.asJava)
    }
  }

  override def buildTargetJvmCompileClasspath(
      params: JvmCompileClasspathParams
  ): CompletableFuture[JvmCompileClasspathResult] = {
    async {
      val items = params.getTargets.asScala.map { id =>
        val info = targetData.get(extractLabel(id))
        val cp = info
          .flatMap(_.jvmTargetInfo)
          .map(_.transitiveCompileTimeJars.map(resolveOutputUri))
          .getOrElse(Nil)
        new JvmCompileClasspathItem(id, cp.asJava)
      }
      new JvmCompileClasspathResult(items.asJava)
    }
  }

  override def buildTargetDependencyModules(
      params: DependencyModulesParams
  ): CompletableFuture[DependencyModulesResult] = {
    async {
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
    }
  }

  override def buildTargetOutputPaths(
      params: OutputPathsParams
  ): CompletableFuture[OutputPathsResult] = {
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

  // Internal helpers

  private def runInitialSync(): Unit = {
    if (!initialSyncDone.compareAndSet(false, true)) return
    try {
      scribe.info("[BazelNative BSP] Running initial aspect sync (//...)...")
      val startMs = System.currentTimeMillis()
      val exitCode = scala.concurrent.Await.result(
        process.syncBuild(aspectsManager.extraSyncFlags),
        scala.concurrent.duration.Duration(5, "min"),
      )
      val elapsed = System.currentTimeMillis() - startMs
      if (exitCode != 0)
        scribe.warn(
          s"[BazelNative BSP] Sync build exited with $exitCode; aspect data may be missing (check build errors)"
        )
      scribe.info(
        s"[BazelNative BSP] Sync completed (exit=$exitCode) in ${elapsed}ms"
      )
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
      !label.startsWith("//.metals/bazel-native-bsp/") &&
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
    caps.setCanDebug(caps.getCanRun() || caps.getCanTest())

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
      outputs.binaryJars.map(resolveOutputUri)
    }
    val runtimeUris = jvm.transitiveRuntimeJars.map(resolveOutputUri)
    val compileUris = jvm.transitiveCompileTimeJars.map(resolveOutputUri)
    (jarUris ++ runtimeUris ++ compileUris).distinct
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
    Option(resolved.getParent).getOrElse(resolved).toUri.toString
  }
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

  private def fallbackClassDir: String =
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
    val LiteralPattern =
      """scala_(?:config|version)\s*(?:\(scala_version\s*=\s*|=\s*)"([^"]+)"""".r
    val VarRefPattern =
      """scala_(?:config|version)\s*(?:\(scala_version\s*=\s*|=\s*)([A-Z_][A-Z_0-9]*)""".r
    def resolveVar(content: String, name: String): Option[String] = {
      val assign =
        s"""(?m)^\\s*${java.util.regex.Pattern.quote(name)}\\s*=\\s*"([^"]+)"""".r
      assign.findFirstMatchIn(content).map(_.group(1))
    }
    List("WORKSPACE", "WORKSPACE.bazel", "MODULE.bazel").iterator
      .map(workspace.resolve)
      .filter(_.isFile)
      .flatMap { file =>
        val content = new String(
          java.nio.file.Files.readAllBytes(file.toNIO),
          java.nio.charset.StandardCharsets.UTF_8,
        )
        LiteralPattern
          .findFirstMatchIn(content)
          .map(_.group(1))
          .orElse(
            VarRefPattern
              .findFirstMatchIn(content)
              .flatMap(m => resolveVar(content, m.group(1)))
          )
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
