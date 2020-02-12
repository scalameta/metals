package scala.meta.internal.pantsbuild

import bloop.config.{Config => C}
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import coursierapi.Dependency
import coursierapi.MavenRepository
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.concurrent.CancellationException
import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.MetalsLogger
import scala.meta.internal.pantsbuild.commands._
import scala.meta.internal.pc.InterruptException
import scala.meta.internal.process.SystemProcess
import scala.meta.io.AbsolutePath
import scala.meta.io.Classpath
import scala.meta.pc.CancelToken
import scala.sys.process.Process
import scala.util.control.NonFatal
import scala.util.Failure
import scala.util.Properties
import scala.util.Success
import scala.util.Try
import ujson.Value
import metaconfig.cli.CliApp
import metaconfig.cli.HelpCommand
import metaconfig.cli.VersionCommand

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
      LinkCommand,
      AmendCommand,
      RemoveCommand
    )
  )

  def main(args: Array[String]): Unit = {
    MetalsLogger.updateDefaultFormat()
    val exit = app.run(args.toList)
    System.exit(exit)
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
      val outputFile = cacheDir.resolve(s"$outputFilename.json")
      val bloopDir = args.out.resolve(".bloop")
      if (Files.isSymbolicLink(bloopDir)) {
        Files.delete(bloopDir)
      }
      Files.createDirectories(bloopDir)
      args.token.checkCanceled()

      val filemap =
        Filemap.fromPants(args.workspace, args.isCache, args.targets)
      val fileCount = filemap.fileCount()
      if (fileCount > args.maxFileCount) {
        val targetSyntax = args.targets.mkString("'", "' '", "'")
        scribe.error(
          s"The target set ${targetSyntax} is too broad, it expands to ${fileCount} source files " +
            s"when the maximum number of allowed source files is ${args.maxFileCount}. " +
            s"To fix this problem, either pass in the flag '--max-file-count ${fileCount}' or" +
            "configure a smaller set of Pants targets."
        )
        throw new CancellationException("too many Pants targets")
      }
      args.onFilemap(filemap)

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
          new BloopPants(args, bloopDir, export, filemap).run()
        case None =>
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
      args: Export,
      outputFile: Path
  )(implicit ec: ExecutionContext): Unit = {
    val command = List[Option[String]](
      Some(args.workspace.resolve("pants").toString()),
      Some("--concurrent"),
      Some(s"--no-quiet"),
      if (args.isSources) Some(s"--export-libraries-sources")
      else None,
      Some(s"--export-output-file=$outputFile"),
      Some(s"export-classpath"),
      Some(s"export")
    ).flatten ++ args.targets
    val shortName = "pants export-classpath export"
    SystemProcess.run(
      shortName,
      command,
      command,
      args.workspace,
      args.token
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
    args: Export,
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
  lazy val testingFrameworkJars: List[Path] =
    List(
      // NOTE(olafur) This is a fork of the official sbt JUnit testing interface
      // https://github.com/olafurpg/junit-interface that reproduces the JUnit test
      // runner in Pants. Most importantly, it automatically registers
      // org.scalatest.junit.JUnitRunner even if there is no `@RunWith`
      // annotation.
      Dependency.of("com.geirsson", "junit-interface", "0.11.10")
    ).flatMap(fetchDependency)
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
    val sourceRoots = PantsConfiguration.sourceRoots(
      AbsolutePath(workspace),
      args.targets
    )
    val isBaseDirectory =
      projects.iterator.filter(_.sources.nonEmpty).map(_.directory).toSet
    // NOTE(olafur): generate synthetic projects to improve the file tree view
    // in IntelliJ. Details: https://github.com/olafurpg/intellij-bsp-pants/issues/7
    val syntheticProjects: List[C.Project] = sourceRoots.flatMap { root =>
      if (isBaseDirectory(root.toNIO)) {
        Nil
      } else {
        val name = root
          .toRelative(AbsolutePath(workspace))
          .toURI(isDirectory = false)
          .toString()
        // NOTE(olafur): cannot be `name + "-root"` since that conflicts with the
        // IntelliJ-generated root project.
        List(toEmptyBloopProject(name + "-project-root", root.toNIO))
      }
    }
    val binaryDependenciesSourcesIterator = getLibraryDependencySources()
    val generatedProjects = new mutable.LinkedHashSet[Path]
    val allProjects = syntheticProjects ::: projects
    val byName = allProjects.map(p => p.name -> p).toMap
    allProjects.foreach { project =>
      // NOTE(olafur): we probably want to generate projects with empty
      // sources/classpath and single dependency on the parent.
      if (!export.cycles.parents.contains(project.name)) {
        val children = export.cycles.children
          .getOrElse(project.name, Nil)
          .flatMap(byName.get)
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
            val newSources = Iterator(
              withBinaryResolution.sources.iterator,
              children.iterator.flatMap(_.sources.iterator)
            ).flatten.distinctBy(identity)
            val newClasspath = Iterator(
              withBinaryResolution.classpath.iterator,
              children.iterator.flatMap(_.classpath.iterator)
            ).flatten.distinctBy(identity)
            val newDependencies = Iterator(
              withBinaryResolution.dependencies.iterator,
              children.iterator.flatMap(_.dependencies.iterator)
            ).flatten
              .filterNot(_ == withBinaryResolution.name)
              .distinctBy(identity)
            withBinaryResolution.copy(
              sources = newSources,
              classpath = newClasspath,
              dependencies = newDependencies
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

  // This method returns an iterator to avoid duplicated `*-sources.jar` references.
  private def getLibraryDependencySources(): Iterator[C.Module] = {
    for {
      target <- export.targets.valuesIterator
      if !target.isTargetRoot
      baseDirectory = target.baseDirectory(workspace)
      sourceDirectory <- enclosingSourceDirectory(baseDirectory)
    } {
      binaryDependencySources += sourceDirectory
    }
    binaryDependencySources.iterator.map(newSourceModule)
  }

  private def toBloopProject(target: PantsTarget): C.Project = {

    val baseDirectory: Path = target.baseDirectory(workspace)

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
      if acyclicDependency.isTargetRoot && !acyclicDependency.targetType.isResourceOrTestResource
    } yield acyclicDependency.name

    val libraries: List[PantsLibrary] = for {
      dependency <- transitiveDependencies
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
    classpath ++= (for {
      dependency <- transitiveDependencies
      if !dependency.isTargetRoot
      entry <- exportClasspath(dependency)
    } yield entry)
    classpath ++= libraries.iterator.flatMap(_.nonSources)
    classpath ++= allScalaJars
    if (target.targetType.isTest) {
      classpath ++= testingFrameworkJars
    }

    binaryDependencySources ++= libraries.flatMap(_.sources)

    val out: Path = bloopDir.resolve(target.directoryName)
    val classDirectory: Path = target.classesDir(bloopDir)
    val javaHome: Option[Path] =
      Option(System.getProperty("java.home")).map(Paths.get(_))

    val resources: List[Path] = for {
      dependency <- transitiveDependencies
      if dependency.targetType.isResourceOrTestResource
      entry <- exportClasspath(dependency)
    } yield entry

    // NOTE(olafur): we put resources on the classpath instead of under "resources"
    // due to an issue how the IntelliJ BSP integration interprets resources.
    classpath ++= resources

    C.Project(
      name = target.name,
      directory = baseDirectory,
      workspaceDir = Some(workspace),
      sources,
      dependencies = dependencies,
      classpath = classpath.toList,
      out = out,
      classesDir = classDirectory,
      resources = None,
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
      resolution = None
    )
  }

  // Returns a Bloop project that has no source code. This project only exists
  // to control for example how the project view is displayed in IntelliJ.
  private def toEmptyBloopProject(name: String, directory: Path): C.Project = {
    val directoryName = BloopPants.makeFilename(name)
    val classDirectory: Path = Files.createDirectories(
      bloopDir.resolve(directoryName).resolve("classes")
    )
    C.Project(
      name = name,
      directory = directory,
      workspaceDir = Some(workspace),
      sources = Nil,
      dependencies = Nil,
      classpath = Nil,
      out = bloopDir.resolve(directoryName),
      classesDir = classDirectory,
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

  private val exportClasspathCache: mutable.Map[String, List[Path]] =
    mutable.Map.empty[String, List[Path]]
  private val exportClasspathDir: AbsolutePath = AbsolutePath(
    workspace.resolve("dist").resolve("export-classpath")
  )
  private val exportClasspathJars = mutable.Map.empty[Path, Option[Path]]
  def exportClasspathJar(
      target: PantsTarget,
      entry: Path,
      suffix: String
  ): Option[Path] = {
    exportClasspathJars.getOrElseUpdate(
      entry, {
        val out = bloopJars.resolve(s"${target.id}$suffix.jar")
        try {
          val attr = Files.readAttributes(entry, classOf[BasicFileAttributes])
          if (attr.isRegularFile()) {
            // Copy jar from `.pants.d/` directory to `.bloop/` directory to ensure that the
            // Bloop classpath is isolated from the Pants classpath.
            Files.copy(entry, out, StandardCopyOption.REPLACE_EXISTING)
            Some(out)
          } else {
            // Leave directory entries unchanged, don't copy them to `.bloop/` directory.
            Some(entry)
          }
        } catch {
          case _: IOException =>
            // Does not exist, ignore this entry.
            None
        }
      }
    )
  }

  private val bloopJars =
    Files.createDirectories(bloopDir.resolve("bloop-jars"))
  private def exportClasspath(target: PantsTarget): List[Path] = {
    exportClasspathCache.getOrElseUpdate(
      target.name, {
        val classpathFile =
          exportClasspathDir.resolve(target.id + "-classpath.txt")
        if (classpathFile.isFile) {
          val classpath =
            Classpath(classpathFile.readText.trim()).entries.map(_.toNIO)
          classpath.iterator.zipWithIndex.flatMap {
            case (entry, 0) => exportClasspathJar(target, entry, "")
            case (entry, i) => exportClasspathJar(target, entry, s"-$i")
          }.toList
        } else {
          Nil
        }
      }
    )
  }

  private def bloopScala: Option[C.Scala] =
    Some(
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
    )

  private def bloopTestFrameworks: Option[C.Test] = {
    Some(
      C.Test(
        frameworks = List(
          C.TestFramework(List("com.geirsson.junit.PantsFramework"))
        ),
        options = C.TestOptions(
          excludes = Nil,
          arguments = Nil
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
