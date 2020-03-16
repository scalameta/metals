package scala.meta.internal.pantsbuild

import bloop.config.{Config => C}
import coursierapi.Dependency
import coursierapi.MavenRepository
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.MetalsLogger
import scala.meta.internal.pantsbuild.commands._
import scala.meta.internal.pc.InterruptException
import scala.meta.internal.process.SystemProcess
import scala.meta.io.AbsolutePath
import scala.meta.pc.CancelToken
import scala.sys.process.Process
import scala.util.control.NonFatal
import scala.util.Failure
import scala.util.Properties
import scala.util.Success
import scala.util.Try
import ujson.Value
import metaconfig.cli.CliApp
import metaconfig.cli.TabCompleteCommand
import metaconfig.cli.HelpCommand
import metaconfig.cli.VersionCommand
import java.{util => ju}
import java.nio.file.FileSystems
import scala.annotation.tailrec
import scala.meta.internal.mtags.MD5

object BloopPants {
  lazy val app: CliApp = CliApp(
    version = BuildInfo.metalsVersion,
    binaryName = "fastpass",
    commands = List(
      HelpCommand,
      VersionCommand,
      CreateCommand,
      RefreshCommand,
      ListCommand,
      InfoCommand,
      OpenCommand,
      SwitchCommand,
      AmendCommand,
      RemoveCommand,
      TabCompleteCommand
    )
  )

  def main(args: Array[String]): Unit = {
    MetalsLogger.updateDefaultFormat()
    val exit = app.run(args.toList)
    System.exit(exit)
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
          "--concurrent",
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

  def bloopInstall(args: Export)(implicit ec: ExecutionContext): Try[Int] =
    interruptedTry {
      val cacheDir = Files.createDirectories(
        args.workspace.resolve(".pants.d").resolve("metals")
      )
      val outputFilename = PantsConfiguration.outputFilename(args.targets)
      val outputFile = cacheDir.resolve(s"$outputFilename-export.json")
      val bloopDir = args.out.resolve(".bloop")
      if (Files.isSymbolicLink(bloopDir)) {
        Files.delete(bloopDir)
      }
      Files.createDirectories(bloopDir)
      args.token.checkCanceled()

      def readJson(file: Path): Option[Value] = {
        Try {
          val text =
            new String(Files.readAllBytes(outputFile), StandardCharsets.UTF_8)
          ujson.read(text)
        }.toOption
      }
      val fromCache: Option[Value] =
        if (!args.isCache) None
        else readJson(outputFile)
      val fromExport: Option[Value] =
        fromCache.orElse {
          runPantsExport(args, outputFile)
          readJson(outputFile)
        }

      fromExport match {
        case Some(value) =>
          val export = PantsExport.fromJson(args, value)
          new BloopPants(args, bloopDir, export).run()
        case None =>
          throw new NoSuchFileException(
            outputFile.toString(),
            null,
            "expected this file to exist after running `./pants export`"
          )
      }
    }

  private def runPantsExport(
      args: Export,
      outputFile: Path
  )(implicit ec: ExecutionContext): Unit = {
    val noSources = if (args.isSources) "" else "no-"
    val command = List[String](
      args.workspace.resolve("pants").toString(),
      "--concurrent",
      s"--no-quiet",
      s"--${noSources}export-dep-as-jar-sources",
      s"--export-dep-as-jar-output-file=$outputFile",
      s"export-dep-as-jar"
    ) ++ args.targets
    val shortName = "pants export-classpath export"
    SystemProcess.run(
      shortName,
      command,
      command,
      args.workspace,
      args.token
    )
  }

  private val nonAlphanumeric = "[^a-zA-Z0-9\\._]".r
  def makeReadableFilename(target: String): String = {
    nonAlphanumeric.replaceAllIn(target, "-")
  }
  def makeJsonFilename(target: String): String = {
    makeReadableFilename(target) + ".json"
  }
  def makeJarFilename(target: String): String = {
    makeReadableFilename(target) + ".jar"
  }
  def makeClassesDirFilename(target: String): String = {
    // Prepend "z_" to separate it from the JSON files when listing the
    // `.bloop/` directory.
    "z_" + MD5.compute(target).take(12)
  }

  private val sourceRootPattern = FileSystems.getDefault.getPathMatcher(
    "glob:**/{main,test,tests,src,3rdparty,3rd_party,thirdparty,third_party}/{resources,scala,java,jvm,proto,python,protobuf,py}"
  )

  private def approximateSourceRoot(dir: Path): Option[Path] = {
    @tailrec def loop(d: Path): Option[Path] = {
      if (sourceRootPattern.matches(d)) Some(d)
      else {
        Option(d.getParent) match {
          case Some(parent) => loop(parent)
          case None => None
        }
      }
    }
    loop(dir)
  }

}

private class BloopPants(
    args: Export,
    bloopDir: Path,
    export: PantsExport
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
  lazy val testingFrameworkJars: List[Path] =
    List(
      // NOTE(olafur) This is a fork of the official sbt JUnit testing interface
      // https://github.com/olafurpg/junit-interface that reproduces the JUnit test
      // runner in Pants. Most importantly, it automatically registers
      // org.scalatest.junit.JUnitRunner even if there is no `@RunWith`
      // annotation.
      Dependency.of("org.scalameta", "junit-interface", "0.5.2")
    ).flatMap(fetchDependency)

  private val mutableJarsHome = workspace.resolve(".pants.d")
  private val bloopJars =
    Files.createDirectories(bloopDir.resolve("bloop-jars"))
  private val toCopyBuffer = new ju.HashMap[Path, Path]()
  private val jarPattern =
    FileSystems.getDefault().getPathMatcher("glob:**.jar")
  private val copiedJars = new ju.HashSet[Path]()
  private val sourcesJars = new ju.HashSet[Path]()
  private val immutableJars = mutable.Map.empty[Path, Path]
  val allScalaJars: List[Path] =
    export.scalaPlatform.compilerClasspath
      .map(jar => toImmutableJar(jar.filename, jar))
      .toList

  def run(): Int = {
    token.checkCanceled()
    val projects = export.targets.valuesIterator
      .filter(_.isTargetRoot)
      .map(toBloopProject)
      .toList
    copyImmutableJars()
    val sourceRoots = PantsConfiguration.sourceRoots(
      AbsolutePath(workspace),
      args.targets
    )
    val isBaseDirectory =
      projects.iterator.filter(_.sources.nonEmpty).map(_.directory).toSet
    val generatedProjects = new mutable.LinkedHashSet[Path]
    val byName = projects.map(p => p.name -> p).toMap
    projects.foreach { project =>
      // NOTE(olafur): we probably want to generate projects with empty
      // sources/classpath and single dependency on the parent.
      if (!export.cycles.parents.contains(project.name)) {
        val children = export.cycles.children
          .getOrElse(project.name, Nil)
          .flatMap(byName.get)
        val finalProject =
          if (children.isEmpty) project
          else {
            val newSources = Iterator(
              project.sources.iterator,
              children.iterator.flatMap(_.sources.iterator)
            ).flatten.distinctBy(identity)
            val newSourcesGlobs = Iterator(
              project.sourcesGlobs.iterator.flatMap(_.iterator),
              children.iterator.flatMap(
                _.sourcesGlobs.iterator.flatMap(_.iterator)
              )
            ).flatten.distinctBy(identity)
            val newClasspath = Iterator(
              project.classpath.iterator,
              children.iterator.flatMap(_.classpath.iterator)
            ).flatten.distinctBy(identity)
            val newDependencies = Iterator(
              project.dependencies.iterator,
              children.iterator.flatMap(_.dependencies.iterator)
            ).flatten
              .filterNot(_ == project.name)
              .distinctBy(identity)
            project.copy(
              sources = newSources,
              sourcesGlobs =
                if (newSourcesGlobs.isEmpty) None
                else Some(newSourcesGlobs),
              classpath = newClasspath,
              dependencies = newDependencies
            )
          }
        val id = export.targets.get(finalProject.name).fold(project.name)(_.id)
        val out = bloopDir.resolve(BloopPants.makeJsonFilename(id))
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

    val baseDirectory: Path = target.baseDirectory(workspace)

    val sources: List[Path] =
      if (target.targetType.isResourceOrTestResource) Nil
      else if (!target.globs.isStatic) Nil
      else if (target.isGeneratedTarget) target.roots.sourceRoots
      else {
        target.globs.staticPaths(workspace) match {
          case Some(paths) => paths
          case _ => Nil
        }
      }
    val sourcesGlobs: Option[List[C.SourcesGlobs]] =
      if (target.targetType.isResourceOrTestResource) None
      else if (target.globs.isStatic) None
      else if (target.globs.isEmpty) None
      else Some(List(target.globs.bloopConfig(workspace, baseDirectory)))

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
      if acyclicDependency.isTargetRoot
    } yield acyclicDependency.dependencyName

    val libraries: List[PantsLibrary] = for {
      dependency <- target :: transitiveDependencies
      libraryName <- dependency.libraries
      // The "$ORGANIZATION:$ARTIFACT" part of Maven library coordinates.
      module = {
        val colon = libraryName.lastIndexOf(':')
        if (colon < 0) libraryName
        else libraryName.substring(0, colon)
      }
      // Respect "excludes" setting in Pants BUILD files to exclude library dependencies.
      if !target.excludes.contains(module)
      library <- export.libraries.get(libraryName)
    } yield library

    val classpath = new mutable.LinkedHashSet[Path]()
    classpath ++= (for {
      dependency <- transitiveDependencies
      if dependency.isTargetRoot
      acyclicDependency = cycles.parents
        .get(dependency.name)
        .flatMap(export.targets.get)
        .getOrElse(dependency)
    } yield acyclicDependency.classesDir(bloopDir))
    classpath ++= libraries.iterator.flatMap(library =>
      library.nonSources.map(path => toImmutableJar(library, path))
    )
    classpath ++= allScalaJars
    if (target.targetType.isTest) {
      classpath ++= testingFrameworkJars
    }

    val resolution = Some(
      C.Resolution(
        (for {
          library <- libraries.iterator
          source <- library.sources
          // NOTE(olafur): avoid sending the same *-sources.jar to reduce the
          // size of the Bloop JSON configs. Both IntelliJ and Metals only need each
          // jar to appear once.
          if !sourcesJars.contains(source)
        } yield {
          sourcesJars.add(source)
          newSourceModule(source)
        }).toList
      )
    )

    val out: Path = bloopDir.resolve(target.directoryName)
    val classesDir: Path = target.classesDir(bloopDir)
    val javaHome: Option[Path] =
      Option(System.getProperty("java.home")).map(Paths.get(_))

    val resources: Option[List[Path]] =
      if (!target.targetType.isResourceOrTestResource) None
      else {
        Some(List(baseDirectory))
      }

    val sourceRoots = BloopPants.approximateSourceRoot(baseDirectory).toList

    C.Project(
      name = target.dependencyName,
      directory = baseDirectory,
      workspaceDir = Some(workspace),
      sources = sources,
      sourcesGlobs = sourcesGlobs,
      sourceRoots = Some(sourceRoots),
      dependencies = dependencies,
      classpath = classpath.toList,
      out = out,
      classesDir = classesDir,
      resources = resources,
      scala = bloopScala,
      java = Some(C.Java(Nil)),
      sbt = None,
      test = bloopTestFrameworks,
      platform = Some(
        C.Platform.Jvm(
          C.JvmConfig(
            javaHome,
            List(
              s"-Duser.dir=$workspace"
            )
          ),
          None
        )
      ),
      resolution = resolution
    )
  }

  // Returns a Bloop project that has no source code. This project only exists
  // to control for example how the project view is displayed in IntelliJ.
  private def toEmptyBloopProject(name: String, directory: Path): C.Project = {
    val directoryName = BloopPants.makeClassesDirFilename(name)
    val classesDir: Path = Files.createDirectories(
      bloopDir.resolve(directoryName).resolve("classes")
    )
    C.Project(
      name = name,
      directory = directory,
      workspaceDir = Some(workspace),
      sources = Nil,
      sourcesGlobs = None,
      sourceRoots = None,
      dependencies = Nil,
      classpath = Nil,
      out = bloopDir.resolve(directoryName),
      classesDir = classesDir,
      // NOTE(olafur): we generate a fake resource directory so that IntelliJ
      // displays this directory in the "Project files tree" view. This needs to
      // be a resource directory instead of a source directory to prevent Bloop
      // from compiling it.
      resources = Some(List(directory)),
      scala = None,
      java = None,
      sbt = None,
      test = None,
      platform = None,
      resolution = None
    )
  }

  private def toImmutableJar(library: PantsLibrary, path: Path): Path = {
    val filename = BloopPants.makeReadableFilename(library.name) + ".jar"
    toImmutableJar(filename, path)
  }
  private def toImmutableJar(filename: String, path: Path): Path = {
    // NOTE(olafur): Jars that live inside $WORKSPACE/.pants.d get overwritten
    // by Pants during compilation. We copy these jars over to the Bloop
    // directory so that the Bloop incremental cache is unaffected by the
    // `./pants compile` tasks that the user is running.
    if (path.startsWith(mutableJarsHome) && jarPattern.matches(path)) {
      toCopyBuffer.computeIfAbsent(
        path,
        _ => {
          val destination = bloopJars.resolve(filename)
          copiedJars.add(destination)
          destination
        }
      )
    } else {
      path
    }
  }
  private def copyImmutableJars(): Unit = {
    toCopyBuffer.entrySet().parallelStream().forEach { entry =>
      val source = entry.getKey()
      val destination = entry.getValue()
      try {
        Files.copy(
          source,
          destination,
          StandardCopyOption.REPLACE_EXISTING
        )
      } catch {
        case e: IOException =>
          scribe.warn(s"failed to copy jar ${entry.getKey()}", e)
      }
    }
    AbsolutePath(bloopJars).list.foreach { absoluteJar =>
      val jar = absoluteJar.toNIO
      if (!copiedJars.contains(jar) && jarPattern.matches(jar)) {
        // Garbage collect unused jars.
        Files.deleteIfExists(jar)
      }
    }
  }

  def bloopScala: Option[C.Scala] =
    Some(
      C.Scala(
        "org.scala-lang",
        "scala-compiler",
        compilerVersion,
        List.empty[String],
        allScalaJars,
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
    )

  private def bloopTestFrameworks: Option[C.Test] = {
    Some(
      C.Test(
        frameworks = List(
          C.TestFramework(List("munit.internal.junitinterface.PantsFramework"))
        ),
        options = C.TestOptions(
          excludes = Nil,
          arguments = Nil
        )
      )
    )
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
    val jsonPattern = FileSystems.getDefault().getPathMatcher("glob:**/*.json")
    AbsolutePath(bloopDir).list
      .filter { path =>
        // Re-implementation of https://github.com/scalacenter/bloop/blob/e014760490bf140e2755eb91260bdaf9a75e4476/integrations/sbt-bloop/src/main/scala/bloop/integrations/sbt/SbtBloop.scala#L1064-L1079
        path.isFile &&
        jsonPattern.matches(path.toNIO) &&
        path.filename != "bloop.settings.json" &&
        !generatedProjects(path.toNIO)
      }
      .foreach { path => Files.deleteIfExists(path.toNIO) }
  }

  // See https://github.com/scalatest/scalatest/pull/1739
  private def fetchDependency(dep: Dependency): List[Path] =
    try {
      coursierapi.Fetch
        .create()
        .withDependencies(dep)
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
