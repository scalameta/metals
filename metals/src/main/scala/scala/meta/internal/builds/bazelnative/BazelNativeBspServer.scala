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
import com.google.gson.JsonParser

import scala.build.bsp.WrappedSourcesParams
import scala.build.bsp.WrappedSourcesResult
import scala.build.bsp.WrappedSourcesItem

/**
 * In-process BSP server that translates BSP requests into Bazel commands
 * and pushes BES events back as BSP notifications.
 *
 * This runs within the Metals JVM and communicates via piped streams.
 */
class BazelNativeBspServer(
    workspace: AbsolutePath,
    process: BazelNativeProcess,
    besServer: BazelNativeBesServer,
    translator: BazelNativeBepTranslator,
)(implicit ec: ExecutionContext)
    extends MetalsBuildServer
    with BazelNativeBspClient {

  private val executor: Executor = (r: Runnable) => ec.execute(r)

  private val gson = new GsonBuilder().create()

  @volatile private var initialized = false
  @volatile private var bazelVersion = "unknown"
  @volatile private var detectedScalaVersion: Option[String] = None
  @volatile private var executionRoot: String = ""
  @volatile private var outputBase: String = ""
  @volatile private var bazelBinDir: String = ""
  @volatile private var buildClient: Option[ch.epfl.scala.bsp4j.BuildClient] =
    None
  private val discoveredTargets =
    new java.util.concurrent.ConcurrentHashMap[
      BuildTargetIdentifier,
      BuildTarget,
    ]()
  private val sourceToTargets =
    new java.util.concurrent.ConcurrentHashMap[
      String,
      java.util.Set[BuildTargetIdentifier],
    ]()
  private val targetClasspaths =
    new java.util.concurrent.ConcurrentHashMap[
      BuildTargetIdentifier,
      List[String],
    ]()

  def setClient(client: ch.epfl.scala.bsp4j.BuildClient): Unit = {
    buildClient = Some(client)
  }

  // -- BazelNativeBspClient implementation (receives BSP notifications from translator) --

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

  override def onBuildLogMessage(params: LogMessageParams): Unit =
    buildClient.foreach(_.onBuildLogMessage(params))

  // -- BSP Server interface implementation --

  override def buildInitialize(
      params: InitializeBuildParams
  ): CompletableFuture[InitializeBuildResult] = {
    scribe.debug(
      s"[BazelNative BSP] Request: buildInitialize"
    )
    CompletableFuture.supplyAsync(
      () => {
        val versionFuture = process.version()
        bazelVersion = scala.concurrent.Await.result(
          versionFuture,
          scala.concurrent.duration.Duration(30, "s"),
        )
        scribe.debug(
          s"[BazelNative BSP] Bazel version: $bazelVersion"
        )

        val infoMap = scala.concurrent.Await.result(
          process.info(),
          scala.concurrent.duration.Duration(30, "s"),
        )
        executionRoot = infoMap.getOrElse("execution_root", "")
        outputBase = infoMap.getOrElse("output_base", "")
        bazelBinDir = infoMap.getOrElse("bazel-bin", "")
        scribe.info(
          s"[BazelNative BSP] execution_root: $executionRoot, output_base: $outputBase, bazel-bin: $bazelBinDir"
        )

        val capabilities = new BuildServerCapabilities()
        capabilities.setCompileProvider(
          new CompileProvider(List("scala", "java").asJava)
        )
        capabilities.setDependencySourcesProvider(true)
        capabilities.setDependencyModulesProvider(true)
        capabilities.setCanReload(true)
        capabilities.setInverseSourcesProvider(true)

        val result = new InitializeBuildResult(
          "Bazel Native",
          bazelVersion,
          "2.1.0",
          capabilities,
        )
        scribe.debug(
          s"[BazelNative BSP] InitializeBuildResult: displayName=Bazel Native, version=$bazelVersion"
        )
        result
      },
      executor,
    )
  }

  override def onBuildInitialized(): Unit = {
    scribe.debug(s"[BazelNative BSP] Build initialized")
    initialized = true
  }

  override def buildShutdown(): CompletableFuture[Object] = {
    scribe.debug(s"[BazelNative BSP] Request: buildShutdown")
    CompletableFuture.supplyAsync(
      () => {
        initialized = false
        besServer.shutdown()
        process.shutdown()
        null: Object
      },
      executor,
    )
  }

  override def onBuildExit(): Unit = {
    scribe.debug(s"[BazelNative BSP] onBuildExit")
  }

  override def workspaceBuildTargets()
      : CompletableFuture[WorkspaceBuildTargetsResult] = {
    scribe.debug(s"[BazelNative BSP] Request: workspaceBuildTargets")
    CompletableFuture.supplyAsync(
      () => {
        if (detectedScalaVersion.isEmpty) {
          detectedScalaVersion = detectScalaVersion()
          scribe.debug(
            s"[BazelNative BSP] Detected Scala version: ${detectedScalaVersion.getOrElse("none")}"
          )
        }

        val queryResult = scala.concurrent.Await.result(
          process.query(
            "kind('scala_library|scala_binary|scala_test|java_library|java_binary|java_test', //...)"
          ),
          scala.concurrent.duration.Duration(60, "s"),
        )
        val labels = queryResult.linesIterator.filter(_.startsWith("//")).toList
        scribe.info(
          s"[BazelNative BSP] Discovered ${labels.size} targets: ${labels.mkString(", ")}"
        )
        sourceToTargets.clear()
        targetClasspaths.clear()

        val classpathsByLabel = resolveAllTargetClasspaths()

        val targets = labels.map { label =>
          val id = new BuildTargetIdentifier(s"${workspace.toURI}?$label")
          val target = new BuildTarget(
            id,
            Collections.emptyList(),
            List("scala", "java").asJava,
            Collections.emptyList(),
            new BuildTargetCapabilities(),
          )
          target.setDisplayName(label)
          target.setBaseDirectory(workspace.toURI.toString)
          target.getCapabilities.setCanCompile(true)
          target.getCapabilities.setCanRun(true)
          target.getCapabilities.setCanTest(true)

          detectedScalaVersion.foreach { sv =>
            val scalaBt = new ScalaBuildTarget(
              "org.scala-lang",
              sv,
              scalaBinaryVersion(sv),
              ScalaPlatform.JVM,
              Collections.emptyList(),
            )
            target.setDataKind(BuildTargetDataKind.SCALA)
            target.setData(gson.toJsonTree(scalaBt))
          }

          classpathsByLabel.get(label).foreach { cp =>
            targetClasspaths.put(id, cp)
          }

          indexSourcesForTarget(label, id)
          discoveredTargets.put(id, target)
          target
        }
        new WorkspaceBuildTargetsResult(targets.asJava)
      },
      executor,
    )
  }

  override def workspaceReload(): CompletableFuture[Object] = {
    scribe.debug(s"[BazelNative BSP] Request: workspaceReload")
    CompletableFuture.supplyAsync(
      () => {
        discoveredTargets.clear()
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

  override def buildTargetCompile(
      params: CompileParams
  ): CompletableFuture[CompileResult] = {
    val targets = params.getTargets.asScala.toList
    scribe.info(
      s"[BazelNative BSP] Request: buildTargetCompile, targets=${targets.size}"
    )

    if (targets.isEmpty) {
      CompletableFuture.completedFuture(new CompileResult(StatusCode.OK))
    } else {
      CompletableFuture.supplyAsync(
        () => {
          val originId =
            Option(params.getOriginId).getOrElse(UUID.randomUUID().toString)
          translator.setOriginId(originId)
          translator.setTargets(targets)
          translator.notifyBuildStarted(originId)

          val labels = targets.map(extractLabel)
          val besPort = besServer.port

          var exitCode = 1
          try {
            exitCode = scala.concurrent.Await.result(
              process
                .build(labels, besPort, onStderr = translator.onBuildStderr),
              scala.concurrent.duration.Duration(10, "min"),
            )
          } catch {
            case e: Exception =>
              scribe.error(s"[BazelNative BSP] Build failed: ${e.getMessage}")
          } finally {
            translator.notifyBuildFinished(originId, exitCode)
            translator.clearState()
          }

          if (exitCode == 0) {
            refreshClasspaths()
          }

          val statusCode =
            if (exitCode == 0) StatusCode.OK else StatusCode.ERROR
          val result = new CompileResult(statusCode)
          result.setOriginId(originId)
          result
        },
        executor,
      )
    }
  }

  override def buildTargetSources(
      params: SourcesParams
  ): CompletableFuture[SourcesResult] = {
    scribe.info(s"[BazelNative BSP] Request: buildTargetSources")
    CompletableFuture.supplyAsync(
      () => {
        val items = params.getTargets.asScala.map { targetId =>
          val label = extractLabel(targetId)
          val sources = try {
            val result = scala.concurrent.Await.result(
              process.query(s"labels(srcs, $label)"),
              scala.concurrent.duration.Duration(30, "s"),
            )
            result.linesIterator
              .filter(_.nonEmpty)
              .map { srcLabel =>
                val uri = labelToFileUri(srcLabel)
                scribe.info(
                  s"[BazelNative BSP] Source: $srcLabel -> $uri"
                )
                new SourceItem(uri, SourceItemKind.FILE, false)
              }
              .toList
          } catch {
            case e: Exception =>
              scribe.warn(
                s"[BazelNative BSP] Failed to query sources for $label: ${e.getMessage}"
              )
              Nil
          }
          new SourcesItem(targetId, sources.asJava)
        }
        new SourcesResult(items.asJava)
      },
      executor,
    )
  }

  override def buildTargetDependencySources(
      params: DependencySourcesParams
  ): CompletableFuture[DependencySourcesResult] = {
    scribe.debug(s"[BazelNative BSP] Request: buildTargetDependencySources")
    CompletableFuture.supplyAsync(
      () => {
        val items = params.getTargets.asScala.map { targetId =>
          val cp = Option(targetClasspaths.get(targetId)).getOrElse(Nil)
          val sourceUris = cp.flatMap(findSourceJar).distinct
          scribe.info(
            s"[BazelNative BSP] dependencySources(${extractLabel(targetId)}): ${sourceUris.size} source jars from ${cp.size} classpath entries"
          )
          new DependencySourcesItem(targetId, sourceUris.asJava)
        }
        new DependencySourcesResult(items.asJava)
      },
      executor,
    )
  }

  override def buildTargetInverseSources(
      params: InverseSourcesParams
  ): CompletableFuture[InverseSourcesResult] = {
    scribe.info(s"[BazelNative BSP] Request: buildTargetInverseSources")
    CompletableFuture.supplyAsync(
      () => {
        val docUri = params.getTextDocument.getUri
        val targets = Option(sourceToTargets.get(docUri))
          .map(_.asScala.toList.asJava)
          .getOrElse(Collections.emptyList[BuildTargetIdentifier]())
        scribe.info(
          s"[BazelNative BSP] inverseSources($docUri) -> ${targets.size()} targets, keys=${sourceToTargets.keySet().asScala.take(5)}"
        )
        new InverseSourcesResult(targets)
      },
      executor,
    )
  }

  override def buildTargetCleanCache(
      params: CleanCacheParams
  ): CompletableFuture[CleanCacheResult] = {
    scribe.debug(s"[BazelNative BSP] Request: buildTargetCleanCache")
    CompletableFuture.completedFuture(new CleanCacheResult(false))
  }

  override def buildTargetRun(
      params: RunParams
  ): CompletableFuture[RunResult] = {
    scribe.debug(s"[BazelNative BSP] Request: buildTargetRun")
    CompletableFuture.completedFuture(new RunResult(StatusCode.OK))
  }

  override def buildTargetTest(
      params: TestParams
  ): CompletableFuture[TestResult] = {
    scribe.debug(s"[BazelNative BSP] Request: buildTargetTest")
    CompletableFuture.completedFuture(new TestResult(StatusCode.OK))
  }

  override def debugSessionStart(
      params: DebugSessionParams
  ): CompletableFuture[DebugSessionAddress] = {
    scribe.debug(
      s"[BazelNative BSP] Request: debugSessionStart (not supported)"
    )
    val future = new CompletableFuture[DebugSessionAddress]()
    future.completeExceptionally(
      new UnsupportedOperationException(
        "Bazel Native does not support debugging yet"
      )
    )
    future
  }

  // -- Scala BSP --

  override def buildTargetScalacOptions(
      params: ScalacOptionsParams
  ): CompletableFuture[ScalacOptionsResult] = {
    scribe.debug(s"[BazelNative BSP] Request: buildTargetScalacOptions")
    CompletableFuture.completedFuture(
      new ScalacOptionsResult(
        params.getTargets.asScala.map { id =>
          val label = extractLabel(id)
          val classDir = resolveClassDirectory(label)
          val cp = Option(targetClasspaths.get(id)).getOrElse(Nil)
          scribe.info(
            s"[BazelNative BSP] scalacOptions($label): ${cp.size} classpath entries, classDir=$classDir"
          )
          new ScalacOptionsItem(
            id,
            Collections.emptyList(),
            cp.asJava,
            classDir,
          )
        }.asJava
      )
    )
  }

  override def buildTargetScalaMainClasses(
      params: ScalaMainClassesParams
  ): CompletableFuture[ScalaMainClassesResult] = {
    scribe.debug(s"[BazelNative BSP] Request: buildTargetScalaMainClasses")
    CompletableFuture.completedFuture(
      new ScalaMainClassesResult(Collections.emptyList())
    )
  }

  override def buildTargetScalaTestClasses(
      params: ScalaTestClassesParams
  ): CompletableFuture[ScalaTestClassesResult] = {
    scribe.debug(s"[BazelNative BSP] Request: buildTargetScalaTestClasses")
    CompletableFuture.completedFuture(
      new ScalaTestClassesResult(Collections.emptyList())
    )
  }

  // -- Java BSP --

  override def buildTargetJavacOptions(
      params: JavacOptionsParams
  ): CompletableFuture[JavacOptionsResult] = {
    scribe.debug(s"[BazelNative BSP] Request: buildTargetJavacOptions")
    CompletableFuture.completedFuture(
      new JavacOptionsResult(
        params.getTargets.asScala.map { id =>
          val label = extractLabel(id)
          val classDir = resolveClassDirectory(label)
          val cp = Option(targetClasspaths.get(id)).getOrElse(Nil)
          new JavacOptionsItem(
            id,
            Collections.emptyList(),
            cp.asJava,
            classDir,
          )
        }.asJava
      )
    )
  }

  // -- JVM BSP --

  override def buildTargetJvmRunEnvironment(
      params: JvmRunEnvironmentParams
  ): CompletableFuture[JvmRunEnvironmentResult] = {
    scribe.debug(s"[BazelNative BSP] Request: buildTargetJvmRunEnvironment")
    CompletableFuture.completedFuture(
      new JvmRunEnvironmentResult(Collections.emptyList())
    )
  }

  override def buildTargetJvmTestEnvironment(
      params: JvmTestEnvironmentParams
  ): CompletableFuture[JvmTestEnvironmentResult] = {
    scribe.debug(s"[BazelNative BSP] Request: buildTargetJvmTestEnvironment")
    CompletableFuture.completedFuture(
      new JvmTestEnvironmentResult(Collections.emptyList())
    )
  }

  override def buildTargetJvmCompileClasspath(
      params: JvmCompileClasspathParams
  ): CompletableFuture[JvmCompileClasspathResult] = {
    scribe.debug(s"[BazelNative BSP] Request: buildTargetJvmCompileClasspath")
    CompletableFuture.completedFuture(
      new JvmCompileClasspathResult(Collections.emptyList())
    )
  }

  // -- Dependency modules --

  override def buildTargetDependencyModules(
      params: DependencyModulesParams
  ): CompletableFuture[DependencyModulesResult] = {
    scribe.debug(s"[BazelNative BSP] Request: buildTargetDependencyModules")
    CompletableFuture.supplyAsync(
      () => {
        val items = params.getTargets.asScala.map { targetId =>
          val cp = Option(targetClasspaths.get(targetId)).getOrElse(Nil)
          val modules = cp.flatMap(buildDependencyModule)
          scribe.info(
            s"[BazelNative BSP] dependencyModules(${extractLabel(targetId)}): ${modules.size} modules from ${cp.size} classpath entries"
          )
          new DependencyModulesItem(targetId, modules.asJava)
        }
        new DependencyModulesResult(items.asJava)
      },
      executor,
    )
  }

  private def buildDependencyModule(
      classpathUri: String
  ): Option[DependencyModule] = {
    try {
      val jarPath = java.nio.file.Paths.get(java.net.URI.create(classpathUri))
      val fileName = jarPath.getFileName.toString
      val baseName = fileName
        .stripSuffix(".jar")
        .stripSuffix("-stamped")

      val artifacts = new java.util.ArrayList[MavenDependencyModuleArtifact]()
      val mainArtifact = new MavenDependencyModuleArtifact(classpathUri)
      artifacts.add(mainArtifact)

      findSourceJar(classpathUri).foreach { srcUri =>
        val srcArtifact = new MavenDependencyModuleArtifact(srcUri)
        srcArtifact.setClassifier("sources")
        artifacts.add(srcArtifact)
      }

      val mavenModule = new MavenDependencyModule("", baseName, "", artifacts)

      val depModule = new DependencyModule(baseName, "")
      depModule.setDataKind(DependencyModuleDataKind.MAVEN)
      depModule.setData(gson.toJsonTree(mavenModule))
      Some(depModule)
    } catch {
      case e: Exception =>
        scribe.debug(
          s"[BazelNative BSP] Failed to build dependency module for $classpathUri: ${e.getMessage}"
        )
        None
    }
  }

  override def buildTargetOutputPaths(
      params: OutputPathsParams
  ): CompletableFuture[OutputPathsResult] = {
    scribe.debug(s"[BazelNative BSP] Request: buildTargetOutputPaths")
    CompletableFuture.completedFuture(
      new OutputPathsResult(Collections.emptyList())
    )
  }

  override def buildTargetResources(
      params: ResourcesParams
  ): CompletableFuture[ResourcesResult] = {
    scribe.debug(s"[BazelNative BSP] Request: buildTargetResources")
    CompletableFuture.completedFuture(
      new ResourcesResult(Collections.emptyList())
    )
  }

  override def onRunReadStdin(params: ReadParams): Unit = {
    scribe.debug(s"[BazelNative BSP] onRunReadStdin (not supported)")
  }

  // -- Wrapped sources (Scala CLI specific, return empty) --

  override def buildTargetWrappedSources(
      params: WrappedSourcesParams
  ): CompletableFuture[WrappedSourcesResult] = {
    CompletableFuture.completedFuture(
      new WrappedSourcesResult(Collections.emptyList[WrappedSourcesItem]())
    )
  }

  private def refreshClasspaths(): Unit = {
    try {
      val updated = resolveAllTargetClasspaths()
      updated.foreach { case (label, cp) =>
        val id = new BuildTargetIdentifier(s"${workspace.toURI}?$label")
        targetClasspaths.put(id, cp)
      }
      scribe.info(
        s"[BazelNative BSP] Refreshed classpaths for ${updated.size} targets after build"
      )
    } catch {
      case e: Exception =>
        scribe.warn(
          s"[BazelNative BSP] Failed to refresh classpaths: ${e.getMessage}"
        )
    }
  }

  /**
   * Runs a single `bazel aquery` to extract the Scalac compile classpath
   * for every target in the workspace, returning a map of label -> classpath URIs.
   */
  private def resolveAllTargetClasspaths(): Map[String, List[String]] = {
    if (executionRoot.isEmpty) {
      scribe.warn(
        "[BazelNative BSP] No execution_root; skipping classpath resolution"
      )
      return Map.empty
    }
    try {
      val raw = scala.concurrent.Await.result(
        process.aquery("mnemonic('Scalac', //...)"),
        scala.concurrent.duration.Duration(60, "s"),
      )
      parseAqueryClasspaths(raw)
    } catch {
      case e: Exception =>
        scribe.warn(
          s"[BazelNative BSP] Failed aquery classpath resolution: ${e.getMessage}"
        )
        Map.empty
    }
  }

  private def parseAqueryClasspaths(
      jsonStr: String
  ): Map[String, List[String]] = {
    val result = scala.collection.mutable.Map[String, List[String]]()
    try {
      val root = JsonParser.parseString(jsonStr).getAsJsonObject
      val actions = root.getAsJsonArray("actions")
      if (actions == null) return Map.empty

      val execRoot = java.nio.file.Paths.get(executionRoot)

      actions.forEach { elem =>
        val action = elem.getAsJsonObject
        val mnemonic =
          Option(action.get("mnemonic")).map(_.getAsString).getOrElse("")
        if (mnemonic == "Scalac") {
          val argsArr = action.getAsJsonArray("arguments")
          if (argsArr != null) {
            val args = new java.util.ArrayList[String]()
            argsArr.forEach(a => args.add(a.getAsString))

            var targetLabel: String = null
            var cpStart = -1
            var cpEnd = args.size()

            var i = 0
            while (i < args.size()) {
              val arg = args.get(i)
              if (arg == "--CurrentTarget" && i + 1 < args.size()) {
                targetLabel = args.get(i + 1)
                i += 2
              } else if (arg == "--Classpath") {
                cpStart = i + 1
                i += 1
              } else if (cpStart >= 0 && arg.startsWith("--")) {
                cpEnd = i
                cpStart = -1 // done
                i += 1
              } else {
                i += 1
              }
            }

            if (targetLabel != null && cpStart >= 0) {
              cpEnd = math.min(cpEnd, args.size())
            }

            if (targetLabel != null) {
              val cpEntries = new java.util.ArrayList[String]()
              // re-scan for classpath range
              var inCp = false
              var j = 0
              while (j < args.size()) {
                val arg = args.get(j)
                if (arg == "--Classpath") {
                  inCp = true
                } else if (inCp && arg.startsWith("--")) {
                  inCp = false
                } else if (inCp) {
                  cpEntries.add(arg)
                }
                j += 1
              }

              val resolvedCp = cpEntries.asScala.toList.flatMap { relPath =>
                val fullJarPath = preferFullJar(relPath)
                val resolved = execRoot.resolve(fullJarPath)
                if (java.nio.file.Files.exists(resolved)) {
                  Some(resolved.toUri.toString)
                } else {
                  val original = execRoot.resolve(relPath)
                  if (java.nio.file.Files.exists(original))
                    Some(original.toUri.toString)
                  else {
                    scribe.debug(
                      s"[BazelNative BSP] Classpath jar not found: $relPath"
                    )
                    None
                  }
                }
              }
              scribe.info(
                s"[BazelNative BSP] Classpath for $targetLabel: ${resolvedCp.size} jars"
              )
              result(targetLabel) = resolvedCp
            }
          }
        }
      }
    } catch {
      case e: Exception =>
        scribe.warn(
          s"[BazelNative BSP] Failed to parse aquery JSON: ${e.getMessage}"
        )
    }
    result.toMap
  }

  /**
   * Convert ijar path to full jar path for richer classpath.
   * e.g. "bazel-out/.../hello-lib-ijar.jar" -> "bazel-out/.../hello-lib.jar"
   */
  private def preferFullJar(relPath: String): String = {
    if (relPath.endsWith("-ijar.jar"))
      relPath.stripSuffix("-ijar.jar") + ".jar"
    else
      relPath
  }

  /**
   * Given a classpath JAR URI, find its corresponding source JAR.
   *
   * For stamped JARs like:
   *   .../bin/external/rules_scala++scala_deps+REPO/REPO.stamp/artifact-stamped.jar
   * we look in output_base/external/REPO/ for *-src.jar or *-sources.jar.
   *
   * For regular external JARs we look for a sibling -src.jar.
   */
  private def findSourceJar(classpathUri: String): Option[String] = {
    if (outputBase.isEmpty) return None
    try {
      val jarPath = java.nio.file.Paths.get(java.net.URI.create(classpathUri))
      val jarStr = jarPath.toString
      val externalBase = java.nio.file.Paths.get(outputBase, "external")

      val repoName = extractExternalRepoName(jarStr)
      repoName.flatMap { repo =>
        val repoDir = externalBase.resolve(repo)
        if (java.nio.file.Files.isDirectory(repoDir)) {
          val srcJars = java.nio.file.Files
            .list(repoDir)
            .filter(p =>
              p.toString.endsWith("-src.jar") ||
                p.toString.endsWith("-sources.jar")
            )
            .toArray
            .map(_.asInstanceOf[java.nio.file.Path])
          srcJars.headOption.map(_.toUri.toString)
        } else None
      }
    } catch {
      case e: Exception =>
        scribe.debug(
          s"[BazelNative BSP] Failed to find source jar for $classpathUri: ${e.getMessage}"
        )
        None
    }
  }

  /**
   * Extract the external repo name from a JAR path.
   * Handles both stamped paths (.../bin/external/REPO/...) and
   * direct external paths (.../external/REPO/...).
   */
  private def extractExternalRepoName(path: String): Option[String] = {
    val externalIdx = path.indexOf("/external/")
    if (externalIdx < 0) return None
    val afterExternal = path.substring(externalIdx + "/external/".length)
    val slashIdx = afterExternal.indexOf('/')
    if (slashIdx > 0) Some(afterExternal.substring(0, slashIdx))
    else None
  }

  private def resolveClassDirectory(label: String): String = {
    if (bazelBinDir.nonEmpty) {
      val stripped = label.stripPrefix("@//").stripPrefix("//")
      val colonIdx = stripped.indexOf(':')
      val (pkg, name) =
        if (colonIdx >= 0)
          (stripped.substring(0, colonIdx), stripped.substring(colonIdx + 1))
        else ("", stripped)
      val binDir = java.nio.file.Paths.get(bazelBinDir)
      val jarPath =
        if (pkg.isEmpty) binDir.resolve(s"$name.jar")
        else binDir.resolve(s"$pkg/$name.jar")
      if (java.nio.file.Files.exists(jarPath))
        jarPath.getParent.toUri.toString
      else
        binDir.toUri.toString
    } else {
      workspace.resolve("bazel-bin").toURI.toString
    }
  }

  private def indexSourcesForTarget(
      label: String,
      id: BuildTargetIdentifier,
  ): Unit = {
    try {
      val result = scala.concurrent.Await.result(
        process.query(s"labels(srcs, $label)"),
        scala.concurrent.duration.Duration(30, "s"),
      )
      result.linesIterator.filter(_.nonEmpty).foreach { srcLabel =>
        val uri = labelToFileUri(srcLabel)
        sourceToTargets
          .computeIfAbsent(
            uri,
            _ => java.util.concurrent.ConcurrentHashMap.newKeySet(),
          )
          .add(id)
      }
    } catch {
      case e: Exception =>
        scribe.warn(
          s"[BazelNative BSP] Failed to index sources for $label: ${e.getMessage}"
        )
    }
  }

  private def labelToFilePath(label: String): String = {
    val stripped = label.stripPrefix("@//").stripPrefix("//")
    val colonIdx = stripped.indexOf(':')
    if (colonIdx >= 0) {
      val pkg = stripped.substring(0, colonIdx)
      val name = stripped.substring(colonIdx + 1)
      if (pkg.isEmpty) name else s"$pkg/$name"
    } else stripped
  }

  private def labelToFileUri(label: String): String =
    workspace.resolve(labelToFilePath(label)).toURI.toString

  private def detectScalaVersion(): Option[String] = {
    val ScalaVersionPattern =
      """scala_(?:config|version)\s*(?:\(scala_version\s*=\s*|=\s*)"([^"]+)"""".r
    val candidates = List("WORKSPACE", "WORKSPACE.bazel", "MODULE.bazel")
    candidates.iterator
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

  private def scalaBinaryVersion(scalaVersion: String): String = {
    if (scalaVersion.startsWith("3.")) "3"
    else scalaVersion.split('.').take(2).mkString(".")
  }

  private def extractLabel(id: BuildTargetIdentifier): String = {
    val uri = id.getUri
    val idx = uri.indexOf('?')
    if (idx >= 0) uri.substring(idx + 1) else uri
  }
}
