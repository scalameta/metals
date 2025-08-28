package scala.meta.internal.metals

import java.nio.file.Path
import java.util.concurrent.ScheduledExecutorService
import java.{util => ju}

import scala.concurrent.Await
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.Duration
import scala.util.Success
import scala.util.Try
import scala.util.control.NonFatal

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.parsing.Trees
import scala.meta.internal.pc.EmptySymbolSearch
import scala.meta.internal.pc.JavaPresentationCompiler
import scala.meta.internal.pc.ScalaPresentationCompiler
import scala.meta.io.AbsolutePath
import scala.meta.pc.CompletionItemPriority
import scala.meta.pc.PresentationCompiler
import scala.meta.pc.SymbolSearch

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import org.eclipse.lsp4j.InitializeParams

class CompilerConfiguration(
    workspace: AbsolutePath,
    config: ClientConfiguration,
    userConfig: () => UserConfiguration,
    buildTargets: BuildTargets,
    buffers: Buffers,
    embedded: Embedded,
    sh: ScheduledExecutorService,
    initializeParams: InitializeParams,
    excludedPackages: () => ExcludedPackagesHandler,
    trees: Trees,
    mtagsResolver: MtagsResolver,
    sourceMapper: SourceMapper,
)(implicit ec: ExecutionContextExecutorService, rc: ReportContext) {

  private val plugins = new CompilerPlugins()

  sealed trait MtagsPresentationCompiler {
    def await: PresentationCompiler
    def shutdown(): Unit
  }

  case class StandaloneCompiler(
      scalaVersion: String,
      symbolSearch: SymbolSearch,
      classpath: Seq[Path],
      referenceCounter: CompletionItemPriority,
  ) extends MtagsPresentationCompiler {
    private val mtags =
      mtagsResolver.resolve(scalaVersion).getOrElse(MtagsBinaries.BuildIn)

    val standalone: PresentationCompiler =
      fromMtags(
        mtags,
        options = Nil,
        classpath ++ Embedded.scalaLibrary(scalaVersion),
        "default",
        symbolSearch,
        referenceCounter,
      )

    def shutdown(): Unit = standalone.shutdown()
    def await: PresentationCompiler = standalone
  }

  object StandaloneCompiler {

    def apply(
        scalaVersion: String,
        classpath: Seq[Path],
        sources: Seq[Path],
        workspaceFallback: Option[SymbolSearch],
        referenceCounter: CompletionItemPriority,
    ): StandaloneCompiler = {
      val search =
        createStandaloneSearch(classpath, sources, workspaceFallback)
      StandaloneCompiler(
        scalaVersion,
        search,
        classpath,
        referenceCounter,
      )
    }
  }

  trait LazyCompiler extends MtagsPresentationCompiler {

    def buildTargetId: BuildTargetIdentifier
    protected def fallback: PresentationCompiler
    protected def newCompiler(
        classpath: Seq[Path],
        srcFiles: Seq[Path] = Seq(),
    ): PresentationCompiler

    protected val presentationCompilerRef =
      new ju.concurrent.atomic.AtomicReference[PresentationCompiler]()

    protected val cancelCompilerPromise: Promise[Unit] = Promise[Unit]()
    protected val presentationCompilerFuture: Future[PresentationCompiler] = {
      for {
        classpath <- buildTargets
          .targetClasspath(buildTargetId, cancelCompilerPromise)
          .getOrElse(Future.successful(Nil))
      } yield {
        val sourceItems = buildTargets.sourceItemsToBuildTargets
          .filter(_._2.iterator().asScala.toList.contains(buildTargetId))
          .map(_._1.toNIO)
          .toList

        // set wasResolved to avoid races on timeout below
        val classpathSeq = classpath.toAbsoluteClasspath.map(_.toNIO).toSeq
        val result = newCompiler(classpathSeq, sourceItems)
        // Request finished, we can remove and shut down the fallback
        Option(presentationCompilerRef.getAndSet(result))
          .foreach(_.shutdown())
        result
      }
    }

    def await: PresentationCompiler = {
      val compilerConfig = config.initialConfig.compilers
      try {
        val pc = presentationCompilerRef.get()
        if (pc != null) {
          pc
        } else {
          val result = Await.result(
            presentationCompilerFuture,
            Duration(compilerConfig.timeoutDelay, compilerConfig.timeoutUnit),
          )
          result
        }
      } catch {
        case _: ju.concurrent.TimeoutException =>
          scribe.warn(
            s"Still waiting for information about classpath, using standalone compiler for now"
          )
          this.synchronized {
            val old = presentationCompilerRef.get()
            if (old != null) old
            else {
              val newFallback = fallback
              presentationCompilerRef.set(newFallback)
              newFallback
            }
          }
      }
    }

    def shutdown(): Unit = {
      cancelCompilerPromise.trySuccess(())
      presentationCompilerFuture.onComplete {
        case Success(value) => value.shutdown()
        case _ =>
      }
      Option(presentationCompilerRef.get()).foreach(_.shutdown())
    }
  }

  case class ScalaLazyCompiler(
      scalaTarget: ScalaTarget,
      mtags: MtagsBinaries,
      search: SymbolSearch,
      referenceCounter: CompletionItemPriority,
      additionalClasspath: Seq[Path] = Nil,
  ) extends LazyCompiler {

    def buildTargetId: BuildTargetIdentifier = scalaTarget.id

    protected def newCompiler(
        classpath: Seq[Path],
        srcFiles: Seq[Path] = Seq(),
    ): PresentationCompiler = {
      val name = scalaTarget.scalac.getTarget().getUri
      val options = enrichWithReleaseOption(scalaTarget)
      // Best Effort option `-Ybest-effort` is useless for PC,
      // and it may unnecesarily dump semanticdb and tasty files
      val bestEffortOpt = "-Ybest-effort"
      val withBetastyOpt = "-Ywith-best-effort-tasty"
      val nonBestEffortOptions =
        if (scalaTarget.isBestEffort)
          options
            .filter(_ != bestEffortOpt)
            .filter(_ != withBetastyOpt) :+ withBetastyOpt
        else options

      val bestEffortDirs = scalaTarget.info
        .getDependencies()
        .asScala
        .flatMap { buildId =>
          if (scalaTarget.isBestEffort)
            buildTargets.scalaTarget(buildId).map(_.bestEffortPath)
          else None
        }
        .toSeq
      val selfBestEffortDir =
        if (scalaTarget.isBestEffort) Seq(scalaTarget.bestEffortPath)
        else Seq.empty

      val sourcePath = srcFiles
      scribe.debug(s"Source path: ${sourcePath.mkString(":")}")

      fromMtags(
        mtags,
        nonBestEffortOptions,
        classpath ++ additionalClasspath ++ bestEffortDirs ++ selfBestEffortDir,
        name,
        search,
        referenceCounter,
        sourcePath,
      )
        .withBuildTargetName(scalaTarget.displayName)
    }

    protected def fallback: PresentationCompiler =
      StandaloneCompiler(
        scalaTarget.scalaVersion,
        Nil,
        Nil,
        Some(search),
        referenceCounter,
      ).standalone

  }

  object ScalaLazyCompiler {

    def forWorksheet(
        scalaTarget: ScalaTarget,
        mtags: MtagsBinaries,
        classpath: Seq[Path],
        sources: Seq[Path],
        workspaceFallback: SymbolSearch,
        referenceCounter: CompletionItemPriority,
    ): ScalaLazyCompiler = {

      val worksheetSearch =
        createStandaloneSearch(classpath, sources, Some(workspaceFallback))

      ScalaLazyCompiler(
        scalaTarget,
        mtags,
        worksheetSearch,
        referenceCounter,
        classpath,
      )
    }
  }

  case class JavaLazyCompiler(
      targetId: BuildTargetIdentifier,
      search: SymbolSearch,
      completionItemPriority: CompletionItemPriority,
  ) extends LazyCompiler {

    def buildTargetId: BuildTargetIdentifier = targetId

    protected def newCompiler(
        classpath: Seq[Path],
        srcFiles: Seq[Path] = Seq(),
    ): PresentationCompiler = {
      val pc = JavaPresentationCompiler()
      configure(pc, search, completionItemPriority)
        .newInstance(
          targetId.getUri(),
          classpath.asJava,
          log.asJava,
          srcFiles.asJava,
        )
    }

    protected def fallback: JavaPresentationCompiler =
      JavaPresentationCompiler()
  }

  private def configure(
      pc: PresentationCompiler,
      search: SymbolSearch,
      completionItemPriority: CompletionItemPriority,
  ): PresentationCompiler =
    pc.withSearch(search)
      .withExecutorService(ec)
      .withCompletionItemPriority(completionItemPriority)
      .withWorkspace(workspace.toNIO)
      .withScheduledExecutorService(sh)
      .withReportsLoggerLevel(MetalsServerConfig.default.loglevel)
      .withConfiguration {
        val options =
          InitializationOptions.from(initializeParams).compilerOptions
        config.initialConfig.compilers
          .update(options)
          .copy(
            _symbolPrefixes = userConfig().symbolPrefixes,
            isCompletionSnippetsEnabled =
              initializeParams.supportsCompletionSnippets,
            _isStripMarginOnTypeFormattingEnabled =
              () => userConfig().enableStripMarginOnTypeFormatting,
            hoverContentType = config.hoverContentType(),
            emitDiagnostics = userConfig().presentationCompilerDiagnostics,
          )
      }

  private def fromMtags(
      mtags: MtagsBinaries,
      options: Seq[String],
      classpathSeq: Seq[Path],
      name: String,
      symbolSearch: SymbolSearch,
      referenceCounter: CompletionItemPriority,
      sourcePath: Seq[Path] = Seq.empty,
  ): PresentationCompiler = {
    val pc = mtags match {
      case MtagsBinaries.BuildIn => new ScalaPresentationCompiler()
      case artifacts: MtagsBinaries.Artifacts =>
        embedded.presentationCompiler(artifacts)
    }

    val filteredOptions = plugins.filterSupportedOptions(options)
    configure(pc, symbolSearch, referenceCounter)
      .newInstance(
        name,
        classpathSeq.asJava,
        (log ++ filteredOptions).asJava,
        sourcePath.asJava,
      )
  }

  private def createStandaloneSearch(
      classpath: Seq[Path],
      sources: Seq[Path],
      workspaceFallback: Option[SymbolSearch],
  ): SymbolSearch = try {
    new StandaloneSymbolSearch(
      workspace,
      classpath.map(AbsolutePath(_)),
      sources.map(AbsolutePath(_)),
      buffers,
      excludedPackages,
      trees,
      buildTargets,
      saveSymbolFileToDisk = !config.isVirtualDocumentSupported(),
      sourceMapper,
      workspaceFallback,
    )
  } catch {
    case NonFatal(error) => {
      scribe.error(
        "Could not create standalone symbol search, please report an issue.",
        error,
      )
      val report =
        Report(
          "standalone-serach-error",
          s"""|occurred while creating classpath search
              |
              |classpath:
              |${classpath.mkString(",")}
              |
              |sources:
              |${sources.mkString(",")}
              |""".stripMargin,
          error,
        )
      rc.unsanitized.create(report)
      EmptySymbolSearch
    }
  }

  private def enrichWithReleaseOption(scalaTarget: ScalaTarget) = {
    val scalacOptions = scalaTarget.scalac.getOptions().asScala.toSeq
    def existsReleaseSetting = scalacOptions.exists(opt =>
      opt.startsWith("-release") ||
        opt.startsWith("--release") ||
        opt.startsWith("-java-output-version")
    )
    if (existsReleaseSetting) scalacOptions
    else {
      def optBuildTargetJvmVersion =
        scalaTarget.jvmVersion
          .flatMap(version => JdkVersion.parse(version))
          .orElse {
            val javaHome =
              scalaTarget.jvmHome
                .flatMap(_.toAbsolutePathSafe)
                .orElse {
                  for {
                    javaHomeString <- userConfig().javaHome.map(_.trim())
                    if (javaHomeString.nonEmpty)
                    javaHome <- Try(AbsolutePath(javaHomeString)).toOption
                  } yield javaHome
                }
            JdkVersion.maybeJdkVersionFromJavaHome(javaHome)
          }

      val releaseVersion =
        for {
          jvmVersion <- optBuildTargetJvmVersion
          metalsJavaVersion <- Option(sys.props("java.version"))
            .flatMap(JdkVersion.parse)
          _ <-
            if (jvmVersion.major < metalsJavaVersion.major) Some(())
            else if (metalsJavaVersion.major > jvmVersion.major) {
              scribe.warn(
                s"""|Your project uses JDK version ${jvmVersion.major} and
                    |Metals server is running on JDK version ${metalsJavaVersion.major}.
                    |This might cause incorrect completions, since
                    |Metals JDK version should be greater or equal the project's JDK version.
                    |""".stripMargin
              )
              None
            } else None
        } yield jvmVersion.major

      releaseVersion match {
        // https://github.com/scala/bug/issues/13045
        case Some(version)
            if version < 17 && scalaTarget.scalaBinaryVersion == "2.13" =>
          /* Filter out -target: and -Xtarget: options, since they are not relevant and
           * might interfere with -release option */
          val filterOutTarget = scalacOptions.filterNot(opt =>
            opt.startsWith("-target:") || opt.startsWith("-Xtarget:")
          )
          filterOutTarget ++ List("-release", version.toString())
        case _ if scalaTarget.scalaBinaryVersion == "2.13" =>
          removeReleaseOptions(scalacOptions)
        case _ =>
          scalacOptions
      }
    }
  }

  private def isHigherThan17(version: String) =
    Try(version.toInt).toOption.exists(_ >= 17)

  private def removeReleaseOptions(options: Seq[String]): Seq[String] = {
    options match {
      case "-release" :: version :: tail if isHigherThan17(version) =>
        removeReleaseOptions(tail)
      case opt :: tail
          if opt.startsWith("-release") && isHigherThan17(
            opt.stripPrefix("-release:")
          ) =>
        removeReleaseOptions(tail)
      case head :: tail => head +: removeReleaseOptions(tail)
      case Nil => options
    }
  }

  private def log: List[String] =
    if (config.initialConfig.compilers.debug) {
      List(
        "-Ypresentation-debug",
        "-Ypresentation-verbose",
        "-Ypresentation-log",
        workspace.resolve(Directories.pc).toString(),
      )
    } else {
      Nil
    }
}
