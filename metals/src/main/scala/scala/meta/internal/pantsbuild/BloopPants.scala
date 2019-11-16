package scala.meta.internal.pantsbuild

import bloop.config.{Config => C}
import java.nio.file.Paths
import java.nio.file.Files
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import scala.collection.mutable
import ujson.Value
import scala.util.Success
import scala.util.Failure
import scala.util.Try
import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.Timer
import scala.meta.internal.metals.Time
import java.nio.file.NoSuchFileException
import scala.meta.internal.mtags.MD5
import scala.util.Properties
import coursierapi.Dependency
import scala.concurrent.ExecutionContext
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.pc.CancelToken
import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.internal.metals.BloopInstall
import com.zaxxer.nuprocess.NuProcessBuilder
import scala.util.control.NonFatal
import scala.meta.internal.pc.InterruptException
import scala.meta.internal.metals.MetalsLogger
import scala.meta.io.AbsolutePath
import scala.meta.internal.io.PathIO
import com.zaxxer.nuprocess.NuProcess
import java.util.concurrent.atomic.AtomicBoolean
import scala.meta.internal.ansi.LineListener
import scala.meta.internal.async.MergedCancelToken
import scala.meta.internal.async.CompletableCancelToken

object BloopPants {

  case class Args(
      isHelp: Boolean = false,
      isCompile: Boolean = true,
      isCache: Boolean = false,
      isList: Boolean = false,
      workspace: Path = PathIO.workingDirectory.toNIO,
      out: Path = PathIO.workingDirectory.toNIO,
      targets: List[String] = Nil
  ) {
    def helpMessage: String =
      """pants-bloop [option ..] <dir ..>
        |
        |Command-line tool to export a Pants build into Bloop JSON config files.
        |The <dir ..> arguments are directories containing BUILD files to export,
        |for example "src/main/scala".
        |
        |  --help
        |    Print this help message
        |  --workspace <dir>
        |    The directory containing the pants build.
        |  --out <dir>
        |    The directory containing the generated Bloop JSON files. Defaults to --workspace if not provided.
        |  --[no-]cache (default=false)
        |    If enabled, cache the result from `./pants export`
        |  --[no-]compile (default=true)
        |    If ena, do not run `./pants compile`
        |""".stripMargin
  }
  object Args {
    def parse(args: List[String]): Args =
      args match {
        case Nil => Args(isHelp = true)
        case _ => parse(args, Args())
      }
    def parse(args: List[String], base: Args): Args = args match {
      case Nil => base
      case "--help" :: tail =>
        Args(isHelp = true)
      case "--workspace" :: workspace :: tail =>
        val dir = AbsolutePath(workspace).toNIO
        val out =
          if (base.out == base.workspace) dir
          else base.out
        parse(tail, base.copy(workspace = dir, out = out))
      case "--out" :: out :: tail =>
        parse(tail, base.copy(out = AbsolutePath(out).toNIO))
      case "--list" :: tail =>
        parse(tail, base.copy(isList = true))
      case "--compile" :: tail =>
        parse(tail, base.copy(isCompile = true))
      case "--no-compile" :: tail =>
        parse(tail, base.copy(isCompile = false))
      case "--cache" :: tail =>
        parse(tail, base.copy(isCache = true))
      case "--no-cache" :: tail =>
        parse(tail, base.copy(isCache = false))
      case tail =>
        base.copy(targets = base.targets ++ tail)
    }
  }

  def main(argStrings: Array[String]): Unit = {
    MetalsLogger.updateDefaultFormat()
    val args = Args.parse(argStrings.toList)
    if (args.isHelp) {
      println(args.helpMessage)
    } else if (args.isList) {
      pantsRoots(AbsolutePath(args.workspace), args.targets)
    } else {
      val workspace = args.workspace
      val targets = args.targets
      val timer = new Timer(Time.system)
      bloopInstall(
        workspace,
        args.out,
        targets,
        isCached = args.isCache,
        isCompile = args.isCompile,
        EmptyCancelToken,
        _ => ()
      )(ExecutionContext.global) match {
        case Failure(exception) =>
          scribe.error(s"bloopInstall failed in $timer", exception)
          sys.exit(1)
        case Success(count) =>
          scribe.info(s"time: exported ${count} Pants target(s) in $timer")
      }
    }
  }

  def pantsRoots(
      workspace: AbsolutePath,
      targets: Iterable[String]
  ): List[AbsolutePath] = {
    val list = pantsList(workspace, targets)
    if (list.isEmpty) Nil
    else {
      val result = mutable.ListBuffer.empty[String]
      var current = list(0)
      result += current
      list.iterator.drop(1).foreach { target =>
        if (!target.startsWith(current)) {
          current = target
          result += current
        }
      }
      result.map(workspace.resolve).toList
    }
  }

  private def pantsList(
      workspace: AbsolutePath,
      targets: Iterable[String]
  ): Array[String] = {
    import scala.sys.process._
    val command = List[String](
      workspace.resolve("pants").toString(),
      "list"
    ) ++ targets
    scribe.info(s"running: '${command.mkString(" ")}")
    Process(command, Some(workspace.toFile)).lineStream_!.iterator
      .map(targetDirectory)
      .toArray
      .sorted
  }

  private def targetDirectory(target: String): String = {
    val colon = target.lastIndexOf(':')
    if (colon < 0) target
    else target.substring(0, colon)
  }

  private def interruptedTry[T](thunk: => T): Try[T] =
    try {
      Success(thunk)
    } catch {
      case NonFatal(e) => Failure(e)
      case e @ InterruptException() => Failure(e)
    }

  def bloopInstall(
      workspace: Path,
      out: Path,
      targets: List[String],
      isCached: Boolean,
      isCompile: Boolean,
      token: CancelToken,
      onTargets: Seq[String] => Unit
  )(implicit ec: ExecutionContext): Try[Int] = interruptedTry {
    val cacheDir =
      Files.createDirectories(workspace.resolve(".pants.d").resolve("metals"))
    val outputFilename = {
      val processed = targets.map(_.replaceAll("[^a-zA-Z0-9]", "")).mkString
      if (processed.isEmpty()) {
        MD5.compute(targets.mkString) // necessary for targets like "::/"
      } else {
        processed
      }
    }
    val outputFile = cacheDir.resolve(s"$outputFilename.json")
    val bloopDir = Files.createDirectories(workspace.resolve(".bloop"))
    token.checkCanceled()

    if (!isCached || !Files.isRegularFile(outputFile)) {
      runPantsExport(workspace, outputFile, targets, token, onTargets)
    }

    if (Files.isRegularFile(outputFile)) {
      val text =
        new String(Files.readAllBytes(outputFile), StandardCharsets.UTF_8)
      val json = ujson.read(text)
      new BloopPants(workspace, bloopDir, targets, json, token).run()
    } else {
      throw new NoSuchFileException(
        outputFile.toString(),
        null,
        "expected this file to exist after running `./pants export`"
      )
    }
  }

  val targetCountThreshold = 500

  private def runPantsExport(
      workspace: Path,
      outputFile: Path,
      targets: List[String],
      token: CancelToken,
      onTargets: Seq[String] => Unit
  )(implicit ec: ExecutionContext): Unit = {
    val pantsBinary = workspace.resolve("pants").toString()
    val commandName =
      s"$pantsBinary list export-classpath export ${targets.mkString(" ")}"
    scribe.info(s"running '$commandName', this may take a while...")
    val listSep = ';'
    val command = List[String](
      pantsBinary,
      s"--no-quiet",
      s"--list-sep='$listSep'",
      s"--export-libraries-sources",
      s"--export-output-file=$outputFile",
      s"list",
      s"export-classpath",
      s"export"
    ) ++ targets
    var first = new AtomicBoolean(true)
    val onTooManyTargets = new CompletableCancelToken()
    val newToken = new MergedCancelToken(List(token, onTooManyTargets))
    val stdout: String => Unit = { line =>
      if (first.compareAndSet(true, false)) {
        val listOutput = line.split(listSep)
        if (listOutput.length > targetCountThreshold) {
          scribe.error(
            s"The target set '${targets.mkString(" ")}' is too broad, it expands to '${listOutput.length}' targets " +
              s"when the maximum number of allowed targets is '$targetCountThreshold'. " +
              s"To fix this problem, configure a smaller set of targets than '${targets.mkString(" ")}'."
          )
          onTooManyTargets.cancel()
        } else {
          onTargets(targets)
        }
      } else {
        scribe.info(line)
      }
    }
    runProcess(
      commandName,
      command,
      workspace,
      newToken,
      new LineListener(stdout)
    )
  }

  def runProcess(
      name: String,
      args: List[String],
      cwd: Path,
      token: CancelToken,
      stdout: LineListener
  )(implicit ec: ExecutionContext): Unit = {
    val exportTimer = new Timer(Time.system)
    var process = Option.empty[NuProcess]
    val handler = new BloopInstall.ProcessHandler(
      stdout,
      new LineListener(err => scribe.info(err))
    )
    // val cancelable = new Cancel
    val pb = new NuProcessBuilder(handler, args.asJava)
    pb.setCwd(cwd)
    val runningProcess = pb.start()
    token.onCancel().asScala.foreach { cancel =>
      if (cancel) {
        BloopInstall.destroyProcess(runningProcess)
      }
    }
    val exit = handler.completeProcess.future.asJava.get()
    token.checkCanceled()
    if (exit.isFailed) {
      val message = s"time: $name failed after $exportTimer"
      scribe.error(message)
      sys.error(message)
    } else {
      scribe.info(s"time: ran $name in $exportTimer")
    }
  }
}

private class BloopPants(
    workspace: Path,
    bloopDir: Path,
    userTargets: List[String],
    json: Value,
    token: CancelToken
)(implicit ec: ExecutionContext) { self =>

  private val cycles = Cycles.findConnectedComponents(json)
  private val scalaCompiler = "org.scala-lang:scala-compiler:"
  private val nonAlphanumeric = "[^a-zA-Z0-9]".r

  private val transitiveClasspath = mutable.Map.empty[String, List[Path]]
  private val isVisited = mutable.Set.empty[String]
  private val binaryDependencySources = mutable.Set.empty[Path]

  val targets: mutable.LinkedHashMap[String, Value] = json.obj("targets").obj
  val libraries: mutable.LinkedHashMap[String, Value] =
    json.obj("libraries").obj
  val compilerVersion: String = libraries.keysIterator
    .collectFirst {
      case module if module.startsWith(scalaCompiler) =>
        module.stripPrefix(scalaCompiler)
    }
    .getOrElse {
      scribe.warn(
        s"missing scala-compiler: falling back to ${Properties.versionNumberString}"
      )
      Properties.versionNumberString
    }
  val allScalaJars: Seq[Path] = {
    val scalaJars = libraries.collect {
      case (module, jar) if isScalaJar(module) =>
        Paths.get(jar.obj("default").str)
    }.toSeq
    val hasScalaCompiler =
      scalaJars.exists(_.getFileName().toString().contains("scala-compiler"))
    if (hasScalaCompiler) {
      scalaJars
    } else {
      scalaJars ++
        coursierapi.Fetch
          .create()
          .addDependencies(
            Dependency.of("org.scala-lang", "scala-compiler", compilerVersion)
          )
          .fetch()
          .asScala
          .map(_.toPath)
    }
  }

  def run(): Int = {
    token.checkCanceled()
    // Create Bloop projects that only contain the classpath of direct
    // dependencies but not transitive dependencies.
    val shallowClasspathProjects = targets.collect {
      case (id, target) if isSupportedTargetType(target) =>
        toBloopProject(id, target)
    }
    val byName = shallowClasspathProjects.map(p => p.name -> p).toMap

    // Add full transitive classpath to Bloop projects.
    val fullClasspathProjects = shallowClasspathProjects.map { p =>
      val children = cycles.children.getOrElse(p.name, Nil)
      val extraSources = children.flatMap(child => byName(child).sources)
      val extraDependencies = children
        .flatMap(child => byName(child).dependencies)
        .filter(_ != p.name)
      val dependencies = (p.dependencies ++ extraDependencies).distinct
      p.copy(
        classpath = getTransitiveClasspath(p.name, byName),
        sources = (p.sources ++ extraSources).distinct,
        dependencies = dependencies.filterNot(_.isBinaryDependency)
      )
    }

    val binaryDependenciesToCompile = mutable.Set.empty[String]
    for {
      project <- shallowClasspathProjects
      if !project.name.isBinaryDependency
      dependency <- project.dependencies.iterator
      if dependency.isBinaryDependency
      dependencyProject <- byName.get(dependency)
      if dependencyProject.sources.nonEmpty
    } {
      binaryDependenciesToCompile += dependency
    }

    val binaryDependencyResolution =
      binaryDependencySources.iterator.map(newSourceModule)
    var generatedProjects = Set.empty[Path]
    fullClasspathProjects.foreach { bloop =>
      if (cycles.parents.contains(bloop.name) ||
        bloop.name.isBinaryDependency) {
        // do nothing
      } else {
        val out = bloopDir.resolve(makeFilename(bloop.name) + ".json")
        val withBinaryResolution =
          if (binaryDependencyResolution.hasNext) {
            val extraResolution =
              bloop.resolution.map(_.modules).getOrElse(Nil)
            bloop.copy(
              resolution = Some(
                C.Resolution(
                  binaryDependencyResolution.toList ++ extraResolution
                )
              )
            )
          } else {
            bloop
          }
        val json = C.File(BuildInfo.bloopVersion, withBinaryResolution)
        _root_.bloop.config.write(json, out)
        generatedProjects += out
      }
    }
    cleanStaleBloopFiles(generatedProjects)
    token.checkCanceled()
    generatedProjects.size
  }

  private val unsupportedTargetType = Set(
    "files", "page", "python_binary", "python_tests", "python_library",
    "python_requirement_library"
  )

  implicit class XtensionTargetString(value: String) {
    def isBinaryDependency: Boolean = {
      !userTargets.exists(target => value.startsWith(target.stripSuffix("::")))
    }
    def baseDirectory: Path =
      workspace.resolve(value.substring(0, value.lastIndexOf(':')))
  }
  implicit class XtensionValue(value: Value) {
    def pantsTargetType: String = value.obj("pants_target_type").str
    def targetType: String = value.obj("target_type").str
    def id: String = value.obj("id").str
    def isResourceTarget: Boolean = targetType == "RESOURCE"
  }
  private def isSupportedTargetType(target: Value): Boolean =
    // Instead of whitelisting which target types we support, we hardcode which
    // known target types we know we don't support. Pants plugins can introduce
    // new target types that are minor variations of for example `scala_library`
    // that we may want to support.
    !unsupportedTargetType.contains(target.pantsTargetType)

  private def toBloopProject(
      id: String,
      target: Value
  ): C.Project = {

    val baseDirectories = for {
      roots <- target.obj.get("roots").toList
      root <- roots.arr
      sourceRoot <- root.obj.get("source_root")
    } yield Paths.get(sourceRoot.str)

    val baseDirectory = id.baseDirectory

    val sources = Globs.fromTarget(workspace, target, baseDirectories).sources()

    // Extract target dependencies.
    val dependsOn = (for {
      dependency <- target.obj("targets").arr
      acyclicDependency = cycles.acyclicDependency(dependency.str)
      if acyclicDependency != id
      if isSupportedTargetType(targets(acyclicDependency))
    } yield acyclicDependency).toList

    // Extract class directories of direct target dependencies.
    val dependencyClassdirectories: List[Path] = (for {
      dependency <- target.obj("targets").arr
    } yield makeClassdirectory(dependency.str)).toList

    // Extract 3rd party library dependencies, and their associated sources.
    val libraryDependencies = for {
      dependency <- target.obj("libraries").arr
      library <- libraries.get(dependency.str)
    } yield library
    val libraryDependencyConfigs =
      List("default", "shaded", "linux-x86_64", "thrift9")
    val libraryDependencyClasspaths =
      getLibraryDependencies(libraryDependencies, libraryDependencyConfigs)
    val sourcesConfigs = List("sources")
    val libraryDependencySources =
      getLibraryDependencies(libraryDependencies, sourcesConfigs)

    // Warn about unfamiliar configurations.
    val knownConfigs = sourcesConfigs ::: libraryDependencyConfigs
    val unknownConfigs = libraryDependencies.flatMap(
      lib => lib.obj.keys.toSet -- knownConfigs
    )
    if (unknownConfigs.nonEmpty) {
      println(
        s"[warning] Unknown configs: ${unknownConfigs.mkString(",")}"
      )
    }

    val out = bloopDir.resolve(makeFilename(id))
    val classDirectory = out.resolve("classes")
    val javaHome = Option(System.getProperty("java.home")).map(Paths.get(_))

    val resources =
      if (target.isResourceTarget) {
        // Resources are relativized by the directory where the BUILD file exists.
        Some(List(baseDirectory))
      } else {
        None
      }

    val test = if (target.pantsTargetType.contains("test")) {
      val scalatest = C.TestFramework(
        List(
          "org.scalatest.tools.Framework",
          "org.scalatest.tools.ScalaTestFramework"
        )
      )
      // These test frameworks are the default output from running `show
      // testFrameworks` in sbt (excluding spec2). The output from `./pants
      // export` doesn't include the configured test frameworks.
      val defaultTestFrameworks = List(
        scalatest,
        C.TestFramework(List("org.scalacheck.ScalaCheckFramework")),
        C.TestFramework(List("com.novocode.junit.JUnitFramework"))
      )
      Some(
        C.Test(
          frameworks = defaultTestFrameworks,
          options = C.TestOptions(
            excludes = Nil,
            arguments = List(
              C.TestArgument(
                List("-o"),
                Some(scalatest)
              )
            )
          )
        )
      )
    } else {
      None
    }

    val resolution: Option[C.Resolution] =
      if (id.isBinaryDependency) {
        binaryDependencySources ++= libraryDependencySources
        binaryDependencySources ++= baseDirectory.enclosingSourceDirectory
        None
      } else {
        Some(
          C.Resolution(
            libraryDependencySources.iterator.map(newSourceModule).toList
          )
        )
      }

    C.Project(
      name = id,
      directory = baseDirectory,
      workspaceDir = Some(workspace),
      sources,
      dependencies = dependsOn,
      classpath = dependencyClassdirectories ++ libraryDependencyClasspaths,
      out = out,
      classesDir = classDirectory,
      resources = resources,
      scala = Some(
        C.Scala(
          "org.scala-lang",
          "scala-compiler",
          compilerVersion,
          List.empty[String],
          allScalaJars.toList,
          None,
          setup = Some(
            C.CompileSetup(
              C.Mixed,
              addLibraryToBootClasspath = true,
              addCompilerToClasspath = false,
              addExtraJarsToClasspath = false,
              manageBootClasspath = true,
              filterLibraryFromClasspath = true
            )
          )
        )
      ),
      java = Some(C.Java(Nil)),
      sbt = None,
      test = test,
      platform = Some(C.Platform.Jvm(C.JvmConfig(javaHome, Nil), None)),
      resolution = resolution
    )
  }

  private def makeClassdirectory(target: String): Path =
    if (target.isBinaryDependency) {
      if (targets(target).isResourceTarget) {
        target.baseDirectory
      } else {
        workspace
          .resolve("dist")
          .resolve("export-classpath")
          .resolve(targets(target).id + "-0.jar")
      }
    } else {
      bloopDir.resolve(makeFilename(target)).resolve("classes")
    }

  private def getLibraryDependencies(
      libraryDependencies: Seq[Value],
      keys: List[String]
  ): Seq[Path] =
    for {
      lib <- libraryDependencies
      path <- keys.collectFirst {
        case key if lib.obj.contains(key) => lib.obj(key)
      }
    } yield Paths.get(path.str)

  private def newSourceModule(source: Path) =
    C.Module(
      "",
      "",
      "",
      None,
      artifacts = List(
        C.Artifact(
          "",
          classifier = Some("sources"),
          None,
          path = source
        )
      )
    )

  private def getTransitiveClasspath(
      name: String,
      byName: Map[String, C.Project]
  ): List[Path] = {
    def computeTransitiveClasspath(): List[Path] = {
      val buf = mutable.Set.empty[Path]
      buf ++= byName(name).classpath
      byName(name).dependencies.foreach { dep =>
        buf ++= getTransitiveClasspath(dep, byName)
      }
      val children = cycles.children.getOrElse(name, Nil)
      children.foreach { child =>
        buf ++= getTransitiveClasspath(child, byName)
      }

      // NOTE: Pants automatically includes the compiler classpath for all
      // targets causing some targets to have an undeclared dependency on
      // scala-compiler even if they don't compile without scala-compiler on the
      // classpath.
      buf ++= allScalaJars

      buf.toList.sorted
    }
    if (isVisited(name)) {
      transitiveClasspath.getOrElse(name, Nil)
    } else {
      isVisited += name
      transitiveClasspath.getOrElseUpdate(name, computeTransitiveClasspath())
    }
  }

  private def isScalaJar(module: String): Boolean =
    module.startsWith(scalaCompiler) ||
      module.startsWith("org.scala-lang:scala-reflect:") ||
      module.startsWith("org.scala-lang:scala-library:") ||
      module.startsWith("org.scala-lang:scala-library:") ||
      module.startsWith("org.fursesource:jansi:") ||
      module.startsWith("jline:jline:")

  private def cleanStaleBloopFiles(
      generatedProjects: Set[Path]
  ): Unit = {
    val ls = Files.list(bloopDir)
    try {
      ls.filter { path =>
          Files.isRegularFile(path) &&
          path.getFileName().toString().endsWith(".json") &&
          !generatedProjects(path)
        }
        .forEach { path =>
          Files.delete(path)
        }
    } finally {
      ls.close()
    }
  }

  private def makeFilename(target: String): String = {
    nonAlphanumeric.replaceAllIn(target, "")
  }

}
