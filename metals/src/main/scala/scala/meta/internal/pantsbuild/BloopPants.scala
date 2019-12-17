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
import scala.meta.io.Classpath
import coursierapi.MavenRepository

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
      val outputFilename = PantsConfiguration.outputFilename(args.targets)
      val outputFile = cacheDir.resolve(s"$outputFilename.json")
      val bloopDir = Files.createDirectories(args.out.resolve(".bloop"))
      args.token.checkCanceled()

      val filemap =
        Filemap.fromPants(args.workspace, args.isCache, args.targets)
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
        val export = PantsExport.fromJson(json)
        new BloopPants(args, bloopDir, export, filemap).run()
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
    val filemap = Filemap.fromPants(workspace.toNIO, isCache = false, targets)
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
    export: PantsExport,
    filemap: Filemap
)(implicit ec: ExecutionContext) { self =>
  def token: CancelToken = args.token
  def workspace: Path = args.workspace
  def userTargets: List[String] = args.targets
  def cycles: Cycles = export.cycles

  private val scalaCompiler = "org.scala-lang:scala-compiler:"
  private val transitiveClasspath = mutable.Map.empty[String, List[Path]]
  private val isVisited = mutable.Set.empty[String]
  private val binaryDependencySources = mutable.Set.empty[Path]

  val compilerVersion: String = export.libraries.keysIterator
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
  lazy val optimizedScalatestFramework: List[Path] =
    fetchScalatestFrameworkJar()
  val allScalaJars: Seq[Path] = {
    val compilerClasspath = export.scalaPlatform.compilerClasspath
    if (compilerClasspath.nonEmpty) compilerClasspath
    else {
      val scalaJars: Seq[Path] = export.libraries
        .collect {
          case (module, jar) if isScalaJar(module) => jar.default
        }
        .flatten
        .toSeq
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
  }

  def run(): Int = {
    token.checkCanceled()
    val projects = export.targets.valuesIterator
      .filter(_.isTargetRoot)
      .map(toBloopProject)
      .toList
    // Only emit library sources in one resolution to avoid duplicated
    // `*-sources.jar` references.
    val binaryDependenciesSourcesIterator =
      binaryDependencySources.iterator.map(newSourceModule)
    val generatedProjects = new mutable.LinkedHashSet[Path]
    val byName = projects.map(p => p.name -> p).toMap
    projects.foreach { project =>
      if (!export.cycles.parents.contains(project.name)) {
        val children =
          export.cycles.children.getOrElse(project.name, Nil).map(byName)
        val withBinaryResolution =
          if (binaryDependenciesSourcesIterator.hasNext) {
            val extraResolution =
              project.resolution.map(_.modules).getOrElse(Nil)
            project.copy(
              resolution = Some(
                C.Resolution(
                  binaryDependenciesSourcesIterator.toList ++ extraResolution
                )
              )
            )
          } else {
            project
          }
        val finalProject =
          if (children.isEmpty) withBinaryResolution
          else {
            val newSources =
              (withBinaryResolution.sources ++ children.flatMap(_.sources)).distinct
            val newClasspath =
              (withBinaryResolution.classpath ++ children.flatMap(_.classpath)).distinct
            withBinaryResolution.copy(
              sources = newSources,
              classpath = newClasspath
            )
          }
        val out =
          bloopDir.resolve(BloopPants.makeJsonFilename(finalProject.name))
        val json = C.File(BuildInfo.bloopVersion, finalProject)
        bloop.config.write(json, out)
        generatedProjects += out
      }
    }
    cleanStaleBloopFiles(generatedProjects)
    token.checkCanceled()
    generatedProjects.size
  }

  private def toBloopProject(target: PantsTarget): C.Project = {

    val baseDirectory: Path = PantsConfiguration
      .baseDirectory(AbsolutePath(workspace), target.name)
      .toNIO

    val sources: List[Path] =
      if (target.targetType.isResource) Nil
      else {
        target.globs.sourceDirectory(workspace) match {
          case Some(dir) => List(dir)
          case _ => filemap.forTarget(target.name).toList
        }
      }

    val transitiveDependencies: List[PantsTarget] = (for {
      dependency <- target.transitiveDependencies
      if dependency != target.name
    } yield export.targets(dependency)).toList

    val dependencies: List[String] = for {
      dependency <- transitiveDependencies
      // Rewrite dependencies on targets that belong to a cyclic component.
      acyclicDependencyName = cycles.acyclicDependency(dependency.name)
      if acyclicDependencyName != target.name
      acyclicDependency = export.targets(acyclicDependencyName)
      if acyclicDependency.isTargetRoot && !acyclicDependency.targetType.isAnyResource
    } yield acyclicDependency.name

    val libraries: List[PantsLibrary] = for {
      dependency <- transitiveDependencies
      libraryName <- dependency.libraries
      library <- export.libraries.get(libraryName)
    } yield library

    val classpath = new mutable.LinkedHashSet[Path]()
    classpath ++= (for {
      dependency <- transitiveDependencies
      if dependency.isTargetRoot
    } yield dependency.classesDir(bloopDir))
    classpath ++= (for {
      dependency <- transitiveDependencies
      if !dependency.isTargetRoot
      entry <- exportClasspath(dependency)
    } yield entry)
    classpath ++= libraries.iterator.flatMap(_.nonSources)
    classpath ++= allScalaJars
    if (target.targetType.isTest) {
      classpath ++= optimizedScalatestFramework
    }

    binaryDependencySources ++= libraries.flatMap(_.sources)

    val out: Path = bloopDir.resolve(target.directoryName)
    val classDirectory: Path = target.classesDir(bloopDir)
    val javaHome: Option[Path] =
      Option(System.getProperty("java.home")).map(Paths.get(_))

    val resources: List[Path] = for {
      dependency <- transitiveDependencies
      if dependency.targetType.isAnyResource
      entry <- exportClasspath(dependency)
    } yield entry

    C.Project(
      name = target.name,
      directory = baseDirectory,
      workspaceDir = Some(workspace),
      sources,
      dependencies = dependencies,
      classpath = classpath.toList,
      out = out,
      classesDir = classDirectory,
      resources = if (resources.isEmpty) None else Some(resources),
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
      test = bloopTestFrameworks,
      platform = Some(C.Platform.Jvm(C.JvmConfig(javaHome, Nil), None)),
      resolution = None
    )
  }

  private val exportClasspathCache: mutable.Map[String, List[Path]] =
    mutable.Map.empty[String, List[Path]]
  private val exportClasspathDir: AbsolutePath = AbsolutePath(
    workspace.resolve("dist").resolve("export-classpath")
  )
  private def exportClasspath(target: PantsTarget): List[Path] = {
    exportClasspathCache.getOrElseUpdate(
      target.name, {
        val classpathFile =
          exportClasspathDir.resolve(target.id + "-classpath.txt")
        if (classpathFile.isFile) {
          Classpath(classpathFile.readText.trim()).entries.map(_.toNIO)
        } else {
          Nil
        }
      }
    )
  }

  private def bloopTestFrameworks: Option[C.Test] = {
    val scalatest = C.TestFramework(
      if (optimizedScalatestFramework.nonEmpty) {
        List("org.scalatest.tools.FasterFramework")
      } else {
        List(
          "org.scalatest.tools.Framework",
          "org.scalatest.tools.ScalaTestFramework"
        )
      }
    )
    // These test frameworks are the default output from running `show
    // testFrameworks` in sbt. The output from `./pants export` doesn't include
    // the configured test frameworks.
    val defaultTestFrameworks = List(
      scalatest,
      C.TestFramework(List("com.novocode.junit.JUnitFramework")),
      C.TestFramework(List("org.scalacheck.ScalaCheckFramework")),
      C.TestFramework(List("org.specs.runner.SpecsFramework")),
      C.TestFramework(
        List(
          "org.specs2.runner.Specs2Framework",
          "org.specs2.runner.SpecsFramework"
        )
      )
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
  }

  private def enclosingSourceDirectory(file: Path): Option[Path] = {
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
      generatedProjects: collection.Set[Path]
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

  // See https://github.com/scalatest/scalatest/pull/1739
  private def fetchScalatestFrameworkJar(): List[Path] =
    try {
      coursierapi.Fetch
        .create()
        .withDependencies(
          Dependency
            .of("com.geirsson", "scalatest-framework_2.12", "0.1.0")
            .withTransitive(false)
        )
        .addRepositories(
          MavenRepository.of(
            "https://oss.sonatype.org/content/repositories/public"
          )
        )
        .fetch()
        .asScala
        .map(_.toPath())
        .toList
    } catch {
      case NonFatal(_) => Nil
    }

}
