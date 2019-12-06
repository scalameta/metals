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
import scala.meta.internal.process.SystemProcess
import scala.meta.pc.CancelToken
import scala.util.control.NonFatal
import scala.meta.internal.pc.InterruptException
import scala.meta.internal.metals.MetalsLogger
import scala.meta.io.AbsolutePath
import scala.meta.internal.ansi.LineListener
import java.util.concurrent.CancellationException
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import scala.sys.process.Process

object BloopPants {

  def main(argStrings: Array[String]): Unit = {
    MetalsLogger.updateDefaultFormat()
    Args.parse(argStrings.toList) match {
      case Left(errors) =>
        errors.foreach { error =>
          scribe.error(error)
        }
        System.exit(1)
      case Right(args) =>
        if (args.isHelp) {
          println(args.helpMessage)
        } else if (args.isRegenerate) {
          bloopRegenerate(
            AbsolutePath(args.workspace),
            args.targets
          )(ExecutionContext.global)
        } else {
          val workspace = args.workspace
          val targets = args.targets
          val timer = new Timer(Time.system)
          val installResult = bloopInstall(args)(ExecutionContext.global)
          installResult match {
            case Failure(exception) =>
              scribe.error(s"bloopInstall failed in $timer", exception)
              sys.exit(1)
            case Success(count) =>
              scribe.info(s"time: exported ${count} Pants target(s) in $timer")
          }
        }
    }
  }

  def bloopAddOwnerOf(
      workspace: AbsolutePath,
      source: AbsolutePath
  ): Seq[BuildTargetIdentifier] = synchronized {
    val targets = pantsOwnerOf(workspace, source)
    if (targets.nonEmpty) {
      val bloopDir = workspace.resolve(".bloop")
      for {
        target <- targets
        jsonFile = bloopDir.resolve(BloopPants.makeJsonFilename(target))
        if jsonFile.isFile
      } {
        val json = ujson.read(jsonFile.readText)
        val sources = json("project")("sources").arr
        val sourceStr = Value.Str(source.toString())
        if (!sources.contains(sourceStr)) {
          sources += sourceStr
          jsonFile.writeText(ujson.write(json, indent = 4))
          scribe.info(s"add source: $jsonFile")
        }
      }
    }
    targets.map { target =>
      val baseDirectory = PantsConfiguration.baseDirectory(workspace, target)
      PantsConfiguration.toBloopBuildTarget(baseDirectory, target)
    }
  }

  def pantsOwnerOf(
      workspace: AbsolutePath,
      source: AbsolutePath
  ): Seq[String] = {
    try {
      val relpath = source.toRelative(workspace).toString()
      val output = Process(
        List[String](
          workspace.resolve("pants").toString(),
          s"--owner-of=$relpath",
          "list"
        ),
        cwd = Some(workspace.toFile)
      ).!!
      output.linesIterator.toSeq.distinct
    } catch {
      case NonFatal(_) =>
        Nil
    }
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

  def bloopInstall(args: Args)(implicit ec: ExecutionContext): Try[Int] =
    interruptedTry {
      val cacheDir = Files.createDirectories(
        args.workspace.resolve(".pants.d").resolve("metals")
      )
      val outputFilename = {
        val processed =
          args.targets.map(_.replaceAll("[^a-zA-Z0-9]", "")).mkString
        if (processed.isEmpty()) {
          MD5.compute(args.targets.mkString) // necessary for targets like "::/"
        } else {
          processed
        }
      }
      val outputFile = cacheDir.resolve(s"$outputFilename.json")
      val bloopDir = Files.createDirectories(args.out.resolve(".bloop"))
      args.token.checkCanceled()

      val filemap = Filemap.fromPants(args.workspace, args.targets)
      val fileCount = filemap.fileCount()
      if (fileCount > args.maxFileCount) {
        val targetSyntax = args.targets.mkString("'", "' '", "'")
        scribe.error(
          s"The target set ${targetSyntax} is too broad, it expands to ${fileCount} source files " +
            s"when the maximum number of allowed source files is ${args.maxFileCount}. " +
            s"To fix this problem, configure a smaller set of Pants targets."
        )
        throw new CancellationException("too many Pants targets")
      }
      args.onFilemap(filemap)

      if (!args.isCache || !Files.isRegularFile(outputFile)) {
        runPantsExport(args, outputFile)
      }

      if (Files.isRegularFile(outputFile)) {
        val text =
          new String(Files.readAllBytes(outputFile), StandardCharsets.UTF_8)
        val json = ujson.read(text)
        new BloopPants(args, bloopDir, json, filemap).run()
      } else {
        throw new NoSuchFileException(
          outputFile.toString(),
          null,
          "expected this file to exist after running `./pants export`"
        )
      }
    }

  def bloopRegenerate(
      workspace: AbsolutePath,
      targets: List[String]
  )(implicit ec: ExecutionContext): Unit = {
    val filemap = Filemap.fromPants(workspace.toNIO, targets)
    val bloopDir = workspace.resolve(".bloop")
    for {
      (target, files) <- filemap.iterator()
      jsonFile = bloopDir.resolve(BloopPants.makeJsonFilename(target))
      if jsonFile.isFile
    } {
      val json = ujson.read(jsonFile.readText)
      val newSources =
        files.iterator.map(file => Value.Str(file.toString)).toBuffer
      json("project")("sources") = newSources
      jsonFile.writeText(ujson.write(json, indent = 4))
    }
  }

  private def runPantsExport(
      args: Args,
      outputFile: Path
  )(implicit ec: ExecutionContext): Unit = {
    val pantsBinary = args.workspace.resolve("pants").toString()
    val command = List[String](
      pantsBinary,
      s"--no-quiet",
      s"--export-libraries-sources",
      s"--export-output-file=$outputFile",
      s"export-classpath",
      s"export"
    ) ++ args.targets
    val shortName = "pants export-classpath export"
    SystemProcess.run(
      shortName,
      command,
      args.workspace,
      args.token,
      LineListener.info
    )
  }

  private val nonAlphanumeric = "[^a-zA-Z0-9]".r
  def makeFilename(target: String): String = {
    nonAlphanumeric.replaceAllIn(target, "")
  }
  def makeJsonFilename(target: String): String = {
    makeReadableFilename(target) + ".json"
  }
  def makeReadableFilename(target: String): String = {
    nonAlphanumeric.replaceAllIn(target, "-")
  }

}

private class BloopPants(
    args: Args,
    bloopDir: Path,
    json: Value,
    filemap: Filemap
)(implicit ec: ExecutionContext) { self =>
  def token: CancelToken = args.token
  def workspace: Path = args.workspace
  def userTargets: List[String] = args.targets

  private val cycles = Cycles.findConnectedComponents(json)
  private val scalaCompiler = "org.scala-lang:scala-compiler:"

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
        val out = bloopDir.resolve(BloopPants.makeJsonFilename(bloop.name))
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
      PantsConfiguration.baseDirectory(AbsolutePath(workspace), value).toNIO
  }
  implicit class XtensionValue(value: Value) {
    def pantsTargetType: String = value.obj("pants_target_type").str
    def targetType: String = value.obj("target_type").str
    def id: String = value.obj("id").str
    def isTestTarget: Boolean = targetType == "TEST"
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

    val sources =
      if (target.isResourceTarget) Nil
      else filemap.forTarget(id).toList

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
      classDir <- makeClassdirectory(dependency.str)
    } yield classDir).toList

    // Extract 3rd party library dependencies, and their associated sources.
    val libraryDependencies = for {
      dependency <- target.obj("libraries").arr
      library <- libraries.get(dependency.str)
    } yield library
    val libraryDependencyConfigs: List[String] =
      List("test", "default", "shaded", "linux-x86_64", "thrift9")
    val libraryDependencyClasspaths =
      getLibraryDependencies(libraryDependencies, libraryDependencyConfigs)
    val sourcesConfigs = List("sources")
    val libraryDependencySources =
      getLibraryDependencies(libraryDependencies, sourcesConfigs)

    // Warn about unfamiliar configurations.
    val knownConfigs = sourcesConfigs ::: libraryDependencyConfigs
    val unknownConfigs = libraryDependencies
      .flatMap(lib => lib.obj.keys.toSet -- knownConfigs)
      .distinct
    if (unknownConfigs.nonEmpty) {
      val kinds = unknownConfigs.mkString(",")
      println(
        s"[warning] Unknown configs for target '${id}' with type '${target.targetType}': ${kinds}"
      )
    }

    val out = bloopDir.resolve(BloopPants.makeFilename(id))
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
        binaryDependencySources ++= enclosingSourceDirectory(baseDirectory)
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

  lazy val dist: mutable.Buffer[Path] = Files
    .list(
      workspace.resolve("dist").resolve("export-classpath")
    )
    .asScala
    .toBuffer

  def enclosingSourceDirectory(file: Path): Option[Path] = {
    def loop(p: Path): Option[Path] = {
      if (p == workspace) None
      else if (p.endsWith("java") || p.endsWith("scala")) Some(p)
      else {
        Option(p.getParent()) match {
          case None => None
          case Some(parent) => loop(parent)
        }
      }
    }
    loop(file)
  }

  private def makeClassdirectory(target: String): List[Path] =
    if (target.isBinaryDependency) {
      if (targets(target).isResourceTarget) {
        List(target.baseDirectory)
      } else {
        dist.iterator.filter { p =>
          val filename = p.filename
          filename.startsWith(targets(target).id) &&
          filename.endsWith(".jar")
        }.toList
      }
    } else {
      List(bloopDir.resolve(BloopPants.makeFilename(target)).resolve("classes"))
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

}
