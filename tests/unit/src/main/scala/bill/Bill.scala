package bill

import java.io.File
import java.io.PrintStream
import java.io.PrintWriter
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.stream.Collectors

import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.reflect.internal.util.BatchSourceFile
import scala.reflect.internal.{util => r}
import scala.reflect.io.AbstractFile
import scala.reflect.io.VirtualFile
import scala.tools.nsc
import scala.tools.nsc.reporters.StoreReporter
import scala.util.Try
import scala.util.control.NonFatal

import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.Embedded
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsLogger
import scala.meta.internal.metals.PositionSyntax._
import scala.meta.internal.metals.RecursivelyDelete
import scala.meta.internal.mtags
import scala.meta.internal.mtags.ClasspathLoader
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j._
import ch.epfl.scala.{bsp4j => b}
import com.google.gson.GsonBuilder
import coursierapi.Dependency
import coursierapi.Fetch
import org.eclipse.lsp4j.jsonrpc.Launcher

/**
 * Bill is a basic build tool that implements BSP server discovery for testing purposes.
 *
 * If you are looking to implement a build server, then some parts of Bill might
 * of interest to you:
 *
 * - server discovery installation of `.bsp/bill.json` files
 * - custom Scala compiler reporter to produce BSP diagnostics.
 *
 * You can try bill by running the main method:
 *
 * {{{
 *  $ bill help
 *  usage: bill install (create server discovery file in .bsp/bill.json)
 *         bill bsp     (start BSP server)
 *         bill help    (print this help)
 *         bill compile (start BSP client and run a single compilation request)
 * }}}
 *
 * Bill is otherwise a pretty crappy build tool:
 *
 * - only one target with `src/` source directory and `out/` class directory
 * - no incremental compilation, every compilation is a clean compile.
 */
object Bill {
  class Server() extends BuildServer with ScalaBuildServer {
    val languages: util.List[String] = Collections.singletonList("scala")
    var client: BuildClient = _
    override def onConnectWithClient(server: BuildClient): Unit =
      client = server
    var workspace: Path = Paths.get(".").toAbsolutePath.normalize()
    // Returns true if we're tracing shutdown requests, used for testing purposes.
    def isShutdownTrace(): Boolean = {
      Files.isRegularFile(workspace.resolve("shutdown-trace"))
    }

    def firstShutdownTimeout(): Option[Long] = {
      val fileName = "first-shutdown-timeout"
      val shouldShutdown =
        Files.isRegularFile(workspace.resolve(fileName))
      if (shouldShutdown) {
        val timeout = Try {
          Files
            .readAllLines(workspace.resolve(fileName))
            .asScala
            .headOption
            .mkString
            .trim()
            .toLong
        }.toOption
        Files.delete(workspace.resolve(fileName))
        timeout
      } else {
        None
      }
    }
    def src: Path = workspace.resolve("src")
    def scalaJars: util.List[String] =
      myClasspath
        .filter(_.getFileName.toString.startsWith("scala-"))
        .map(_.toUri.toString)
        .toList
        .asJava
    val target: BuildTarget = {
      val scalaTarget = new ScalaBuildTarget(
        "org.scala-lang",
        mtags.BuildInfo.scalaCompilerVersion,
        "2.12",
        ScalaPlatform.JVM,
        scalaJars
      )
      val id = new BuildTargetIdentifier("id")
      val capabilities = new BuildTargetCapabilities(true, false, false)
      val result = new BuildTarget(
        id,
        Collections.singletonList("tag"),
        languages,
        Collections.emptyList(),
        capabilities
      )
      result.setDisplayName("id")
      result.setData(scalaTarget)
      result
    }
    val reporter = new StoreReporter
    val out: AbsolutePath = AbsolutePath(workspace.resolve("out.jar"))
    Files.createDirectories(out.toNIO.getParent())
    lazy val g: nsc.Global = {
      val settings = new nsc.Settings()
      settings.classpath.value =
        myClasspath.map(_.toString).mkString(File.pathSeparator)
      settings.Yrangepos.value = true
      settings.d.value = out.toString
      new nsc.Global(settings, reporter)
    }

    override def buildInitialize(
        params: InitializeBuildParams
    ): CompletableFuture[InitializeBuildResult] = {
      Future {
        workspace = Paths.get(URI.create(params.getRootUri))
        MetalsLogger.setupLspLogger(AbsolutePath(workspace), true)
        if (isShutdownTrace()) {
          println("trace: initialize")
        }
        val capabilities = new BuildServerCapabilities
        capabilities.setCompileProvider(new CompileProvider(languages))
        new InitializeBuildResult("Bill", "1.0", "2.0.0-M2", capabilities)
      }.logError("initialize").asJava
    }
    override def onBuildInitialized(): Unit = {}
    override def buildShutdown(): CompletableFuture[AnyRef] = {
      if (isShutdownTrace()) {
        // Artifically delay shutdown to test that running "Connect to build server"
        // waits for the shutdown request to respond before initializing a new build
        // server connection.
        Thread.sleep(1000)
        println("trace: shutdown")
      }
      CompletableFuture.completedFuture(null)
    }
    override def onBuildExit(): Unit = {
      System.exit(0)
    }
    override def workspaceBuildTargets()
        : CompletableFuture[WorkspaceBuildTargetsResult] = {
      CompletableFuture.completedFuture {
        new WorkspaceBuildTargetsResult(Collections.singletonList(target))
      }
    }
    override def buildTargetSources(
        params: SourcesParams
    ): CompletableFuture[SourcesResult] = {
      Files.createDirectories(src)
      CompletableFuture.completedFuture {
        new SourcesResult(
          List(
            new SourcesItem(
              target.getId,
              List(
                new SourceItem(
                  src.toUri.toString,
                  SourceItemKind.DIRECTORY,
                  false
                )
              ).asJava
            )
          ).asJava
        )
      }
    }
    override def buildTargetInverseSources(
        params: InverseSourcesParams
    ): CompletableFuture[InverseSourcesResult] = ???
    override def buildTargetDependencySources(
        params: DependencySourcesParams
    ): CompletableFuture[DependencySourcesResult] = {
      val scalaLib = Dependency.of(
        "org.scala-lang",
        "scala-library",
        mtags.BuildInfo.scalaCompilerVersion
      )

      CompletableFuture.completedFuture {
        val sources = Fetch
          .create()
          .withDependencies(scalaLib)
          .addRepositories(Embedded.repositories: _*)
          .fetch()
          .map(_.toPath)
          .asScala

        new DependencySourcesResult(
          List(
            new DependencySourcesItem(
              target.getId,
              sources.map(_.toUri.toString).asJava
            )
          ).asJava
        )
      }
    }
    override def buildTargetResources(
        params: ResourcesParams
    ): CompletableFuture[ResourcesResult] = ???

    private val hasError = mutable.Set.empty[AbstractFile]
    def publishDiagnostics(): Unit = {
      val byFile = reporter.infos.groupBy(_.pos.source.file)
      val fixedErrors = hasError.filterNot(byFile.contains)
      fixedErrors.foreach { file =>
        client.onBuildPublishDiagnostics(
          new PublishDiagnosticsParams(
            new TextDocumentIdentifier(file.name),
            target.getId,
            List().asJava,
            true
          )
        )
      }
      hasError --= fixedErrors
      byFile.foreach {
        case (file, infos) =>
          def toBspPos(pos: r.Position, offset: Int): b.Position = {
            val line = pos.source.offsetToLine(offset)
            val column0 = pos.source.lineToOffset(line)
            val column = offset - column0
            new b.Position(line, column)
          }
          val diagnostics = infos.iterator
            .filter(_.pos.isDefined)
            .map { info =>
              val p = info.pos
              val start =
                toBspPos(info.pos, if (p.isRange) p.start else p.point)
              val end =
                toBspPos(info.pos, if (p.isRange) p.end else p.point)
              val severity = info.severity match {
                case reporter.ERROR => DiagnosticSeverity.ERROR
                case reporter.WARNING => DiagnosticSeverity.WARNING
                case reporter.INFO => DiagnosticSeverity.INFORMATION
                case _ => DiagnosticSeverity.HINT
              }
              val diagnostic = new Diagnostic(new b.Range(start, end), info.msg)
              diagnostic.setSeverity(severity)
              diagnostic
            }
            .toList
          val uri = file.name
          val params =
            new PublishDiagnosticsParams(
              new TextDocumentIdentifier(uri),
              target.getId,
              diagnostics.asJava,
              true
            )
          client.onBuildPublishDiagnostics(params)
          hasError += file
      }
    }

    override def buildTargetCompile(
        params: CompileParams
    ): CompletableFuture[CompileResult] = {
      CompletableFuture.completedFuture {
        reporter.reset()
        val run = new g.Run()
        val sources: List[BatchSourceFile] =
          if (Files.isDirectory(src)) {
            Files
              .walk(src)
              .collect(Collectors.toList())
              .asScala
              .iterator
              .filter(_.getFileName.toString.endsWith(".scala"))
              .map(path => {
                val text =
                  new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
                val chars = text.toCharArray
                new BatchSourceFile(new VirtualFile(path.toUri.toString), chars)
              })
              .toList
          } else {
            Nil
          }
        run.compileSources(sources)
        publishDiagnostics()
        val exit =
          if (reporter.hasErrors) StatusCode.ERROR
          else StatusCode.OK
        new CompileResult(exit)
      }
    }
    override def buildTargetTest(
        params: TestParams
    ): CompletableFuture[TestResult] = ???
    override def buildTargetRun(
        params: RunParams
    ): CompletableFuture[RunResult] = ???
    override def buildTargetCleanCache(
        params: CleanCacheParams
    ): CompletableFuture[CleanCacheResult] = {
      CompletableFuture.completedFuture {
        val count = RecursivelyDelete(out)
        val plural = if (count != 1) "s" else ""
        new CleanCacheResult(s"deleted $count file$plural", true)
      }
    }

    override def buildTargetScalacOptions(
        params: ScalacOptionsParams
    ): CompletableFuture[ScalacOptionsResult] = {
      CompletableFuture.completedFuture {
        new ScalacOptionsResult(
          List(
            new ScalacOptionsItem(
              target.getId,
              List().asJava,
              scalaJars,
              out.toURI.toASCIIString
            )
          ).asJava
        )
      }
    }
    override def buildTargetScalaTestClasses(
        params: ScalaTestClassesParams
    ): CompletableFuture[ScalaTestClassesResult] = ???
    override def buildTargetScalaMainClasses(
        params: ScalaMainClassesParams
    ): CompletableFuture[ScalaMainClassesResult] = ???
  }

  def myClassLoader: ClassLoader =
    this.getClass.getClassLoader
  def myClasspath: Seq[Path] =
    ClasspathLoader
      .getURLs(myClassLoader)
      .map(url => Paths.get(url.toURI))

  def cwd: Path = Paths.get(System.getProperty("user.dir"))

  def handleBsp(): Unit = {
    val stdout = System.out
    val stdin = System.in
    val home = Paths.get(".bill").toAbsolutePath
    Files.createDirectories(home)
    val executor = Executors.newCachedThreadPool()
    val log = home.resolve("bill.log")
    val trace = home.resolve("bill.trace.json")
    val logStream = new PrintStream(
      Files.newOutputStream(
        log,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING
      )
    )
    System.setOut(logStream)
    System.setErr(logStream)
    try {
      val server = new Server()
      val traceWrites = new PrintWriter(
        Files.newOutputStream(
          trace,
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING
        )
      )
      val launcher = new Launcher.Builder[BuildClient]()
        .traceMessages(traceWrites)
        .setOutput(stdout)
        .setInput(stdin)
        .setLocalService(server)
        .setRemoteInterface(classOf[BuildClient])
        .setExecutorService(executor)
        .create()
      server.client = launcher.getRemoteProxy
      val listening = launcher.startListening()
      server.firstShutdownTimeout().foreach { timeout =>
        Future {
          Thread.sleep(timeout)
          System.exit(1)
        }
      }
      listening.get()
    } catch {
      case NonFatal(e) =>
        e.printStackTrace(stdout)
        e.printStackTrace(logStream)
        System.exit(1)
    } finally {
      executor.shutdown()
    }
    stdout.println("error: abnormal exit path")
    println(s"error: abnormal exit path")
  }

  /**
   * Installed this build tool only for the local workspace */
  def installWorkspace(
      directory: Path,
      name: String = "Bill"
  ): Unit = {
    handleInstall(directory.resolve(".bsp"), name)
  }

  /**
   * Installed this build server globally for the machine */
  def installGlobal(
      directory: Path,
      name: String = "Bill"
  ): Unit = {
    handleInstall(directory.resolve("bsp"), name)
  }

  private def handleInstall(directory: Path, name: String = "Bill"): Unit = {
    val java =
      Paths.get(System.getProperty("java.home")).resolve("bin").resolve("java")
    val classpath = myClasspath.mkString(File.pathSeparator)
    val details = new BspConnectionDetails(
      name,
      List(
        java.toString,
        "-classpath",
        classpath,
        "bill.Bill",
        "bsp"
      ).asJava,
      BuildInfo.metalsVersion,
      BuildInfo.bspVersion,
      List("scala").asJava
    )
    val json = new GsonBuilder().setPrettyPrinting().create().toJson(details)
    val bspJson = directory.resolve(s"${name.toLowerCase()}.json")
    Files.createDirectories(directory)
    Files.write(bspJson, json.getBytes(StandardCharsets.UTF_8))
    println(s"installed: $bspJson")
  }

  def handleCompile(wd: Path): Unit = {
    import scala.concurrent.ExecutionContext.Implicits.global
    import scala.meta.internal.metals.MetalsEnrichments._
    val server = new Server()
    val client = new BuildClient {
      override def onBuildShowMessage(params: ShowMessageParams): Unit = ???
      override def onBuildLogMessage(params: LogMessageParams): Unit = ???
      override def onBuildTaskStart(params: TaskStartParams): Unit = ???
      override def onBuildTaskProgress(params: TaskProgressParams): Unit = ???
      override def onBuildTaskFinish(params: TaskFinishParams): Unit = ???
      override def onBuildPublishDiagnostics(
          params: PublishDiagnosticsParams
      ): Unit = {
        val path = params.getTextDocument.getUri.toAbsolutePath
        val input = path.toInput
        params.getDiagnostics.asScala.foreach { diag =>
          val pos = diag.getRange.toMeta(input)
          val message = pos.formatMessage(
            diag.getSeverity.toString.toLowerCase(),
            diag.getMessage
          )
          println(message)
        }
      }
      override def onBuildTargetDidChange(params: DidChangeBuildTarget): Unit =
        ???
    }
    server.onConnectWithClient(client)
    val result = for {
      _ <-
        server
          .buildInitialize(
            new InitializeBuildParams(
              "Metals",
              BuildInfo.metalsVersion,
              BuildInfo.bspVersion,
              wd.toUri.toString,
              new BuildClientCapabilities(
                Collections.singletonList("scala")
              )
            )
          )
          .asScala
      _ = server.onBuildInitialized()
      buildTargets <- server.workspaceBuildTargets().asScala
      _ <-
        server
          .buildTargetCompile(
            new CompileParams(buildTargets.getTargets.map(_.getId))
          )
          .asScala
    } yield ()
    Await.result(result, Duration("1min"))
  }

  def handleHelp(): Unit = {
    println(
      s"""bill v${BuildInfo.metalsVersion}
         |usage: bill install (create server discovery file in .bsp/bill.json)
         |       bill bsp     (start BSP server)
         |       bill help    (print this help)
         |       bill compile (start BSP client and run a single compilation request)
       """.stripMargin
    )
  }

  def main(args: Array[String]): Unit = {
    args.toList match {
      case List("help" | "--help" | "-help" | "-h") => handleHelp()
      case List("install") => handleInstall(cwd)
      case List("bsp") => handleBsp()
      case List("compile") => handleCompile(cwd)
      case List("compile", wd) => handleCompile(Paths.get(wd))
      case unknown =>
        println(s"Invalid arguments: ${unknown.mkString(" ")}")
        handleHelp()
        System.exit(1)
    }
  }
}
