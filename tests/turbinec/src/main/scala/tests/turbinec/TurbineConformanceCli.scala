package tests.turbinec

import com.google.common.collect.ImmutableList
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.turbine.main.Main
import com.google.turbine.options.LanguageVersion
import com.google.turbine.options.TurbineOptions
import java.io.File
import java.io.FileReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.jar.JarFile
import org.apache.maven.model.io.xpp3.MavenXpp3Reader
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.util.Printer
import org.objectweb.asm.util.Textifier
import org.objectweb.asm.util.TraceClassVisitor
import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.sys.process.Process
import scala.sys.process.ProcessLogger
import scala.util.Try
import scala.util.Using
import scala.meta.metals.extract.DependencyModule
import scala.meta.metals.extract.MavenExtractor

object TurbineConformanceCli {
  def main(args: Array[String]): Unit = {
    parseArgs(args.toList) match {
      case Left(error) =>
        System.err.println(s"Error: $error")
        System.err.println("Use --help for usage information")
        System.exit(1)
      case Right(None) =>
        System.exit(0)
      case Right(Some(config)) =>
        run(config) match {
          case Left(error) =>
            System.err.println(s"Error: $error")
            System.exit(1)
          case Right(_) =>
            System.exit(0)
        }
    }
  }

  private sealed trait Command {
    def name: String
  }
  private object Command {
    case object Export extends Command { val name = "export" }
    case object Compare extends Command { val name = "compare" }
    case object All extends Command { val name = "all" }
  }

  private final case class Config(
      command: Command = Command.Compare,
      workspace: Path = Paths.get(".").toAbsolutePath.normalize,
      mbtJson: Option[Path] = None,
      scalaVersion: String = "2.13",
      includeTests: Boolean = false,
      showDiffs: Int = 20,
      verbose: Boolean = false,
      javacRelease: Option[String] = None,
  ) {
    def mbtJsonPath: Path = mbtJson.getOrElse(workspace.resolve(".metals/mbt.json"))
  }

  private final case class BuildConfig(
      dependencyModules: Seq[DependencyModule],
      classpath: Seq[String],
      sourcepaths: Seq[String],
      generatedSourceDirs: Seq[String],
      outputDirs: Seq[String],
      scalaVersion: String,
      buildTool: String,
      workspace: String,
      generatedAt: String,
  ) {
    def classpathPaths: Seq[Path] = classpath.map(Paths.get(_))
    def sourcepathPaths: Seq[Path] = sourcepaths.map(Paths.get(_))
    def generatedSourcePaths: Seq[Path] = generatedSourceDirs.map(Paths.get(_))
    def outputDirPaths: Seq[Path] = outputDirs.map(Paths.get(_))
  }

  private final case class ModuleConfig(
      moduleDir: Path,
      compileSourceRoots: Seq[Path],
      testSourceRoots: Seq[Path],
      outputDir: Option[Path],
      testOutputDir: Option[Path],
      buildDir: Option[Path],
  )

  private def run(config: Config): Either[String, Unit] = {
    config.command match {
      case Command.Export =>
        runExport(config).map(_ => ())
      case Command.Compare =>
        runCompare(config)
      case Command.All =>
        runExport(config).flatMap(_ => runCompare(config))
    }
  }

  private def runExport(config: Config): Either[String, BuildConfig] = {
    val workspace = config.workspace
    if (!Files.isDirectory(workspace)) {
      return Left(s"Workspace does not exist: $workspace")
    }
    val pom = workspace.resolve("pom.xml")
    if (!Files.isRegularFile(pom)) {
      return Left(s"pom.xml not found in workspace: $workspace")
    }

    log(config, s"Exporting Maven build config from $workspace")

    val dependencyModules = MavenExtractor
      .extract(workspace, resolveSources = false, config.verbose)
      .map(mods => filterByScalaVersion(mods, config.scalaVersion))

    dependencyModules.flatMap { modules =>
      val moduleDirs = collectModuleDirs(workspace)
      val moduleConfigs = moduleDirs.foldLeft(Right(List.empty[ModuleConfig]): Either[String, List[ModuleConfig]]) {
        case (acc, dir) =>
          for {
            configs <- acc
            module <- readModuleConfig(config, dir)
          } yield configs :+ module
      }

      moduleConfigs.flatMap { configs =>
        val sourcepaths = distinctPaths(
          configs.flatMap(_.compileSourceRoots) ++
            (if (config.includeTests) configs.flatMap(_.testSourceRoots) else Nil)
        )

        val outputDirs = distinctPaths(
          configs.flatMap(_.outputDir) ++
            (if (config.includeTests) configs.flatMap(_.testOutputDir) else Nil)
        )

        val generatedSourceDirs = distinctPaths(
          configs.flatMap(m => detectGeneratedSourceDirs(m, config.scalaVersion))
        )

        val classpath = distinctStrings(modules.map(_.jar))

        val buildConfig = BuildConfig(
          dependencyModules = modules,
          classpath = classpath,
          sourcepaths = sourcepaths.map(_.toString),
          generatedSourceDirs = generatedSourceDirs.map(_.toString),
          outputDirs = outputDirs.map(_.toString),
          scalaVersion = config.scalaVersion,
          buildTool = "maven",
          workspace = workspace.toAbsolutePath.normalize.toString,
          generatedAt = Instant.now().toString,
        )

        BuildConfigJson.write(config.mbtJsonPath, buildConfig).map { _ =>
          println(
            s"Wrote build config to ${config.mbtJsonPath} (deps=${modules.size}, sources=${sourcepaths.size}, outputs=${outputDirs.size})"
          )
          buildConfig
        }
      }
    }
  }

  private def runCompare(config: Config): Either[String, Unit] = {
    val buildConfig = BuildConfigJson.read(config.mbtJsonPath)
    buildConfig.flatMap { cfg =>
      val sources = listSources(cfg.sourcepathPaths ++ cfg.generatedSourcePaths)
      if (sources.isEmpty) {
        return Left("No sources found from sourcepaths/generatedSourceDirs; run export or check paths")
      }

      val classpath = cfg.classpathPaths.filter(path => Files.exists(path))
      if (classpath.isEmpty) {
        log(config, "Warning: classpath is empty after filtering non-existent entries")
      }

      val outputDirs = cfg.outputDirPaths
      if (outputDirs.isEmpty) {
        return Left("No outputDirs found in build config; run export")
      }

      log(config, s"Compiling ${sources.size} sources with Turbine")

      val outDir = Files.createTempDirectory("turbine-conformance")
      val turbineJar = outDir.resolve("turbine.jar")

      val options = TurbineOptions.builder()
      configureBootClasspath(options, config.javacRelease)
      options.setSources(ImmutableList.copyOf(sources.map(_.toString).asJava))
      options.setClassPath(ImmutableList.copyOf(classpath.map(_.toString).asJava))
      options.setOutput(turbineJar.toString)

      try {
        Main.compile(options.build())
      } catch {
        case e: Exception =>
          return Left(s"Turbine compilation failed: ${e.getMessage}")
      }

      val turbineClasses = filterStableClasses(readJarClasses(turbineJar))
      val baselineResult = readDirClasses(outputDirs)
      val baselineClasses = filterStableClasses(baselineResult.classes)

      if (baselineClasses.isEmpty) {
        return Left("Baseline outputDirs contained no .class files; ensure Maven build is complete")
      }

      println(
        s"Turbine classes: ${turbineClasses.size}, baseline classes: ${baselineClasses.size}, sources: ${sources.size}"
      )

      if (baselineResult.duplicates.nonEmpty) {
        println(s"Warning: ${baselineResult.duplicates.size} duplicate class names found in baseline outputs")
        if (config.verbose) {
          baselineResult.duplicates.take(20).foreach { case (name, paths) =>
            println(s"  $name <- ${paths.mkString(", ")}")
          }
        }
      }

      val diffCollector = new DiffCollector(config.showDiffs)
      compareSubset("baseline", baselineClasses, "turbine", turbineClasses, diffCollector)

      println(
        s"Missing classes: ${diffCollector.missingClasses}, mismatched members: ${diffCollector.mismatchedMembers}"
      )

      if (diffCollector.hasDiffs) {
        if (diffCollector.output.nonEmpty) {
          println(diffCollector.output)
        }
        Left("Conformance gaps detected")
      } else {
        println("No conformance gaps detected")
        Right(())
      }
    }
  }

  private def parseArgs(args: List[String]): Either[String, Option[Config]] = {
    def loop(args: List[String], config: Config): Either[String, Option[Config]] = {
      args match {
        case Nil => Right(Some(config))
        case "--help" :: _ =>
          printHelp()
          Right(None)
        case "export" :: rest =>
          loop(rest, config.copy(command = Command.Export))
        case "compare" :: rest =>
          loop(rest, config.copy(command = Command.Compare))
        case "all" :: rest =>
          loop(rest, config.copy(command = Command.All))
        case "--workspace" :: path :: rest =>
          loop(rest, config.copy(workspace = Paths.get(path).toAbsolutePath.normalize))
        case "--mbt-json" :: path :: rest =>
          loop(rest, config.copy(mbtJson = Some(Paths.get(path).toAbsolutePath.normalize)))
        case "--scala-version" :: version :: rest =>
          loop(rest, config.copy(scalaVersion = version))
        case "--include-tests" :: rest =>
          loop(rest, config.copy(includeTests = true))
        case "--show-diffs" :: count :: rest =>
          Try(count.toInt).toOption match {
            case Some(value) => loop(rest, config.copy(showDiffs = value))
            case None => Left(s"Invalid value for --show-diffs: $count")
          }
        case "--javac-release" :: release :: rest =>
          loop(rest, config.copy(javacRelease = Some(release)))
        case "--verbose" :: rest =>
          loop(rest, config.copy(verbose = true))
        case value :: rest if !value.startsWith("-") =>
          loop(rest, config.copy(workspace = Paths.get(value).toAbsolutePath.normalize))
        case unknown :: _ =>
          Left(s"Unknown option: $unknown")
      }
    }

    loop(args, Config())
  }

  private def printHelp(): Unit = {
    println("""turbinec - Turbine Scala conformance CLI
              |
              |Usage:
              |  turbinec/run -- [export|compare|all] [options]
              |
              |Options:
              |  --workspace <path>     Spark workspace directory (defaults to .)
              |  --mbt-json <path>      Output/input JSON path (default: <workspace>/.metals/mbt.json)
              |  --scala-version <ver>  Filter Scala dependencies (default: 2.13)
              |  --include-tests        Include test sources/output dirs
              |  --javac-release <ver>  Override --release for Turbine (e.g., 8, 11, 17)
              |  --show-diffs <n>        Max number of detailed diffs to print (default: 20)
              |  --verbose              Enable verbose logging
              |  --help                 Show this help message
              |
              |Examples:
              |  sbt "turbinec/run -- export --workspace /Users/olafurpg/dev/apache/spark"
              |  sbt "turbinec/run -- compare --workspace /Users/olafurpg/dev/apache/spark"
              |  sbt "turbinec/run -- all --workspace /Users/olafurpg/dev/apache/spark"
              |""".stripMargin)
  }

  private def collectModuleDirs(workspace: Path): Seq[Path] = {
    val visited = mutable.LinkedHashSet.empty[Path]
    def visit(dir: Path): Unit = {
      val normalized = dir.toAbsolutePath.normalize
      if (!visited.add(normalized)) {
        return
      }
      val pom = normalized.resolve("pom.xml")
      if (!Files.isRegularFile(pom)) {
        return
      }
      readPom(pom) match {
        case Left(_) =>
        case Right(model) =>
          val modules = Option(model.getModules).map(_.asScala.toList).getOrElse(Nil)
          modules.foreach { modulePath =>
            visit(normalized.resolve(modulePath))
          }
      }
    }
    visit(workspace)
    visited.toSeq
  }

  private def readModuleConfig(config: Config, moduleDir: Path): Either[String, ModuleConfig] = {
    val pom = moduleDir.resolve("pom.xml")
    if (!Files.isRegularFile(pom)) {
      return Left(s"pom.xml not found: $pom")
    }
    for {
      compileRoots <- evalPaths(config, moduleDir, "project.compileSourceRoots")
      testRoots <- if (config.includeTests)
        evalPaths(config, moduleDir, "project.testCompileSourceRoots")
      else Right(Nil)
      outputDir <- evalPaths(config, moduleDir, "project.build.outputDirectory").map(_.headOption)
      testOutputDir <- if (config.includeTests)
        evalPaths(config, moduleDir, "project.build.testOutputDirectory").map(_.headOption)
      else Right(None)
      buildDir <- evalPaths(config, moduleDir, "project.build.directory").map(_.headOption)
    } yield ModuleConfig(
      moduleDir = moduleDir,
      compileSourceRoots = compileRoots,
      testSourceRoots = testRoots,
      outputDir = outputDir,
      testOutputDir = testOutputDir,
      buildDir = buildDir,
    )
  }

  private def evalPaths(config: Config, moduleDir: Path, expression: String): Either[String, Seq[Path]] = {
    val cmd = Seq(
      "./mvnw",
      "-q",
      "-f",
      moduleDir.resolve("pom.xml").toString,
      "-DskipTests",
      "-Djgit.dirtyWorkingTree=warning",
      "-DforceStdout",
      "help:evaluate",
      s"-Dexpression=$expression",
    )

    val result = runCommand(cmd, config.workspace)
    if (result.exitCode != 0) {
      return Left(s"mvn help:evaluate failed for $expression in $moduleDir: ${result.stderr.trim}")
    }
    val values = parseMavenOutput(result.stdout)
    val paths = values.map { value =>
      val path = Paths.get(value)
      if (path.isAbsolute) path else moduleDir.resolve(path).normalize
    }
    Right(paths)
  }

  private def parseMavenOutput(output: String): Seq[String] = {
    val lines = output
      .linesIterator
      .map(_.trim)
      .filter(line => line.nonEmpty && !isMavenLogLine(line))
      .toList

    if (lines.isEmpty) {
      return Nil
    }
    if (lines.length == 1) {
      val single = lines.head
      if (single == "null") {
        Nil
      } else if (single.startsWith("[") && single.endsWith("]")) {
        val inner = single.stripPrefix("[").stripSuffix("]").trim
        if (inner.isEmpty) Nil
        else inner.split(",").map(_.trim).filter(_.nonEmpty).toList
      } else {
        List(single)
      }
    } else {
      lines
    }
  }

  private def isMavenLogLine(line: String): Boolean = {
    (line.startsWith("[") && (line.contains("INFO") || line.contains("WARNING") || line.contains("ERROR"))) ||
    line.startsWith("Downloading") ||
    line.startsWith("Downloaded")
  }

  private def readPom(pom: Path) = {
    Try {
      Using.resource(new FileReader(pom.toFile)) { reader =>
        new MavenXpp3Reader().read(reader)
      }
    }.toEither.left.map(_.getMessage)
  }

  private def detectGeneratedSourceDirs(module: ModuleConfig, scalaVersion: String): Seq[Path] = {
    val out = mutable.LinkedHashSet.empty[Path]

    def consider(path: Path): Unit = {
      out += path.toAbsolutePath.normalize
    }

    def isGenerated(path: Path): Boolean = {
      val normalized = path.toAbsolutePath.normalize
      module.buildDir.exists(dir => normalized.startsWith(dir.toAbsolutePath.normalize)) ||
      normalized.toString.contains(s"${File.separator}target${File.separator}") ||
      normalized.toString.contains(s"${File.separator}build${File.separator}")
    }

    module.compileSourceRoots.foreach { root =>
      if (isGenerated(root)) consider(root)
    }
    module.testSourceRoots.foreach { root =>
      if (isGenerated(root)) consider(root)
    }

    module.buildDir.foreach { dir =>
      consider(dir.resolve("generated-sources"))
      consider(dir.resolve("generated-test-sources"))
      consider(dir.resolve(s"scala-$scalaVersion").resolve("src_managed"))
    }

    out.toSeq
  }

  private def listSources(directories: Seq[Path]): Seq[Path] = {
    val out = mutable.LinkedHashSet.empty[Path]
    directories.foreach { dir =>
      if (Files.isDirectory(dir)) {
        val stream = Files.walk(dir)
        try {
          stream.iterator().asScala.foreach { path =>
            val name = path.toString
            if (Files.isRegularFile(path) && (name.endsWith(".scala") || name.endsWith(".java"))) {
              out += path.toAbsolutePath.normalize
            }
          }
        } finally {
          stream.close()
        }
      }
    }
    out.toSeq
  }

  private def readJarClasses(jar: Path): Map[String, Array[Byte]] = {
    val out = new mutable.LinkedHashMap[String, Array[Byte]]()
    val jarFile = new JarFile(jar.toFile)
    try {
      val entries = jarFile.entries()
      while (entries.hasMoreElements) {
        val entry = entries.nextElement()
        if (!entry.isDirectory && entry.getName.endsWith(".class")) {
          val name = entry.getName.stripSuffix(".class")
          val bytes = jarFile.getInputStream(entry).readAllBytes()
          out.put(name, bytes)
        }
      }
    } finally {
      jarFile.close()
    }
    out.toMap
  }

  private final case class BaselineResult(
      classes: Map[String, Array[Byte]],
      duplicates: Map[String, List[String]],
  )

  private def readDirClasses(outputDirs: Seq[Path]): BaselineResult = {
    val out = new mutable.LinkedHashMap[String, Array[Byte]]()
    val duplicates = mutable.LinkedHashMap.empty[String, List[String]]
    outputDirs.foreach { root =>
      if (Files.isDirectory(root)) {
        val stream = Files.walk(root)
        try {
          stream.iterator().asScala.foreach { path =>
            if (Files.isRegularFile(path) && path.toString.endsWith(".class")) {
              val rel = root.relativize(path).toString
              val name = rel
                .stripSuffix(".class")
                .replace(File.separatorChar, '/')
              if (out.contains(name)) {
                val updated = duplicates.getOrElse(name, Nil) :+ path.toString
                duplicates.put(name, updated)
              } else {
                out.put(name, Files.readAllBytes(path))
              }
            }
          }
        } finally {
          stream.close()
        }
      }
    }
    BaselineResult(out.toMap, duplicates.toMap)
  }

  private def filterStableClasses(classes: Map[String, Array[Byte]]): Map[String, Array[Byte]] = {
    val out = new mutable.LinkedHashMap[String, Array[Byte]]()
    classes.foreach { case (name, bytes) =>
      val node = new ClassNode()
      new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES)
      if (!isLocal(node) && !isAnonymous(node)) {
        out.put(name, bytes)
      }
    }
    out.toMap
  }

  private def isLocal(node: ClassNode): Boolean = node.outerMethod != null

  private def isAnonymous(node: ClassNode): Boolean =
    node.innerClasses.asScala.exists(inner => inner.name == node.name && inner.innerName == null)

  private final class DiffCollector(max: Int) {
    var missingClasses: Int = 0
    var mismatchedMembers: Int = 0
    private var printed: Int = 0
    private val sb = new StringBuilder

    def hasDiffs: Boolean = missingClasses > 0 || mismatchedMembers > 0

    def output: String = sb.toString

    def missingClass(name: String, expected: Array[Byte]): Unit = {
      missingClasses += 1
      if (printed < max) {
        printed += 1
        sb.append(s"missing class $name\n")
        sb.append(dumpSingle(name, expected)).append('\n')
      }
    }

    def mismatch(
        className: String,
        detail: String,
        expected: Array[Byte],
        actual: Array[Byte],
    ): Unit = {
      mismatchedMembers += 1
      if (printed < max) {
        printed += 1
        sb.append(s"$detail ($className)\n")
        sb.append(dumpPair("expected", expected, "actual", actual)).append('\n')
      }
    }
  }

  private def compareSubset(
      expectedLabel: String,
      expected: Map[String, Array[Byte]],
      actualLabel: String,
      actual: Map[String, Array[Byte]],
      diffs: DiffCollector,
  ): Unit = {
    val expectedInfos = toClassInfos(expected)
    val actualInfos = toClassInfos(actual)

    expectedInfos.foreach { case (name, e) =>
      actualInfos.get(name) match {
        case None =>
          diffs.missingClass(name, expected(name))
        case Some(a) =>
          val isModuleClass = name.endsWith("$")
          if (e.access != a.access) {
            diffs.mismatch(name, "class access mismatch", expected(name), actual(name))
          }
          if (!isModuleClass || e.superName != "java/lang/Object") {
            if (e.superName != a.superName) {
              diffs.mismatch(name, "class superclass mismatch", expected(name), actual(name))
            }
          }
          if (isModuleClass) {
            if (!e.interfaces.forall(a.interfaces.contains)) {
              diffs.mismatch(name, "class interfaces mismatch", expected(name), actual(name))
            }
          } else if (e.interfaces != a.interfaces) {
            diffs.mismatch(name, "class interfaces mismatch", expected(name), actual(name))
          }
          if (!e.annotations.visible.forall(a.annotations.visible.contains)) {
            diffs.mismatch(name, "class visible annotations mismatch", expected(name), actual(name))
          }
          if (!e.annotations.invisible.forall(a.annotations.invisible.contains)) {
            diffs.mismatch(name, "class invisible annotations mismatch", expected(name), actual(name))
          }

          e.methods.foreach { case (methodName, methodInfo) =>
            a.methods.get(methodName) match {
              case None =>
                diffs.mismatch(name, s"missing method $methodName", expected(name), actual(name))
              case Some(actualMethod) =>
                if (methodInfo.access != actualMethod.access) {
                  diffs.mismatch(name, s"method access mismatch: $methodName", expected(name), actual(name))
                }
                if (methodInfo.exceptions != actualMethod.exceptions) {
                  diffs.mismatch(name, s"method exceptions mismatch: $methodName", expected(name), actual(name))
                }
                if (!methodInfo.annotations.visible.forall(actualMethod.annotations.visible.contains)) {
                  diffs.mismatch(
                    name,
                    s"method visible annotations mismatch: $methodName",
                    expected(name),
                    actual(name),
                  )
                }
                if (!methodInfo.annotations.invisible.forall(actualMethod.annotations.invisible.contains)) {
                  diffs.mismatch(
                    name,
                    s"method invisible annotations mismatch: $methodName",
                    expected(name),
                    actual(name),
                  )
                }
            }
          }

          e.fields.foreach { case (fieldName, fieldInfo) =>
            a.fields.get(fieldName) match {
              case None =>
                diffs.mismatch(name, s"missing field $fieldName", expected(name), actual(name))
              case Some(actualField) =>
                if (fieldInfo.access != actualField.access) {
                  diffs.mismatch(name, s"field access mismatch: $fieldName", expected(name), actual(name))
                }
                if (!fieldInfo.annotations.visible.forall(actualField.annotations.visible.contains)) {
                  diffs.mismatch(
                    name,
                    s"field visible annotations mismatch: $fieldName",
                    expected(name),
                    actual(name),
                  )
                }
                if (!fieldInfo.annotations.invisible.forall(actualField.annotations.invisible.contains)) {
                  diffs.mismatch(
                    name,
                    s"field invisible annotations mismatch: $fieldName",
                    expected(name),
                    actual(name),
                  )
                }
            }
          }
      }
    }
  }

  private final case class AnnotationSet(visible: List[String], invisible: List[String])

  private object AnnotationSet {
    def from(visible: java.util.List[AnnotationNode], invisible: java.util.List[AnnotationNode]): AnnotationSet = {
      AnnotationSet(collect(visible), collect(invisible))
    }

    private def collect(annos: java.util.List[AnnotationNode]): List[String] = {
      if (annos == null || annos.isEmpty) {
        Nil
      } else {
        annos.asScala.map(_.desc).toList.sorted
      }
    }
  }

  private final case class MemberInfo(
      access: Int,
      annotations: AnnotationSet,
      exceptions: List[String],
  )

  private object MemberInfo {
    def from(node: MethodNode): MemberInfo = {
      val access = normalizeAccess(node.access, MethodAccessMask)
      val annotations = AnnotationSet.from(node.visibleAnnotations, node.invisibleAnnotations)
      val exceptions = Option(node.exceptions).map(_.asScala.toList).getOrElse(Nil).sorted
      MemberInfo(access, annotations, exceptions)
    }

    def from(node: FieldNode): MemberInfo = {
      val access = normalizeAccess(node.access, FieldAccessMask)
      val annotations = AnnotationSet.from(node.visibleAnnotations, node.invisibleAnnotations)
      MemberInfo(access, annotations, Nil)
    }
  }

  private final case class ClassInfo(
      access: Int,
      superName: String,
      interfaces: List[String],
      annotations: AnnotationSet,
      methods: Map[String, MemberInfo],
      fields: Map[String, MemberInfo],
  )

  private object ClassInfo {
    def from(node: ClassNode): ClassInfo = {
      val access = normalizeAccess(node.access, ClassAccessMask)
      val ifaces = Option(node.interfaces).map(_.asScala.toList).getOrElse(Nil).sorted
      val annotations = AnnotationSet.from(node.visibleAnnotations, node.invisibleAnnotations)

      val methods = new java.util.TreeMap[String, MemberInfo]()
      node.methods.forEach { method =>
        methods.put(method.name + method.desc, MemberInfo.from(method))
      }

      val fields = new java.util.TreeMap[String, MemberInfo]()
      node.fields.forEach { field =>
        fields.put(field.name + field.desc, MemberInfo.from(field))
      }

      ClassInfo(access, node.superName, ifaces, annotations, methods.asScala.toMap, fields.asScala.toMap)
    }
  }

  private def toClassInfos(classes: Map[String, Array[Byte]]): Map[String, ClassInfo] = {
    val out = mutable.LinkedHashMap.empty[String, ClassInfo]
    classes.foreach { case (name, bytes) =>
      val node = new ClassNode()
      new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES)
      out.put(name, ClassInfo.from(node))
    }
    out.toMap
  }

  private def normalizeAccess(access: Int, mask: Int): Int = access & mask

  private val ClassAccessMask =
    Opcodes.ACC_PUBLIC |
      Opcodes.ACC_PROTECTED |
      Opcodes.ACC_PRIVATE |
      Opcodes.ACC_FINAL |
      Opcodes.ACC_ABSTRACT |
      Opcodes.ACC_INTERFACE |
      Opcodes.ACC_ENUM |
      Opcodes.ACC_ANNOTATION

  private val FieldAccessMask =
    Opcodes.ACC_PUBLIC |
      Opcodes.ACC_PROTECTED |
      Opcodes.ACC_PRIVATE |
      Opcodes.ACC_STATIC |
      Opcodes.ACC_FINAL |
      Opcodes.ACC_VOLATILE |
      Opcodes.ACC_TRANSIENT

  private val MethodAccessMask =
    Opcodes.ACC_PUBLIC |
      Opcodes.ACC_PROTECTED |
      Opcodes.ACC_PRIVATE |
      Opcodes.ACC_STATIC |
      Opcodes.ACC_FINAL |
      Opcodes.ACC_ABSTRACT |
      Opcodes.ACC_SYNCHRONIZED |
      Opcodes.ACC_NATIVE |
      Opcodes.ACC_STRICT |
      Opcodes.ACC_VARARGS

  private def dumpSingle(name: String, bytes: Array[Byte]): String = {
    val sb = new StringBuilder
    sb.append(s"=== $name ===\n")
    sb.append(textify(bytes, skipDebug = true))
    sb.toString()
  }

  private def dumpPair(
      expectedLabel: String,
      expected: Array[Byte],
      actualLabel: String,
      actual: Array[Byte],
  ): String = {
    val sb = new StringBuilder
    sb.append(s"=== $expectedLabel ===\n")
    sb.append(textify(expected, skipDebug = true)).append('\n')
    sb.append(s"=== $actualLabel ===\n")
    sb.append(textify(actual, skipDebug = true)).append('\n')
    sb.toString()
  }

  private def textify(bytes: Array[Byte], skipDebug: Boolean): String = {
    val textifier: Printer = new Textifier()
    val sw = new java.io.StringWriter()
    val flags = ClassReader.SKIP_FRAMES | ClassReader.SKIP_CODE | (if (skipDebug) ClassReader.SKIP_DEBUG else 0)
    new ClassReader(bytes)
      .accept(new TraceClassVisitor(null, textifier, new java.io.PrintWriter(sw, true)), flags)
    sw.toString
      .linesIterator
      .map(trimTrailingSpaces)
      .mkString("\n")
  }

  private def trimTrailingSpaces(line: String): String = line.reverse.dropWhile(_ == ' ').reverse

  private def configureBootClasspath(options: TurbineOptions.Builder, releaseOverride: Option[String]): Unit = {
    val boot = Option(System.getProperty("sun.boot.class.path")).getOrElse("")
    val entries = boot
      .split(File.pathSeparator)
      .filter(_.nonEmpty)
      .map(Paths.get(_))
      .filter(Files.exists(_))
      .toList

    if (entries.nonEmpty) {
      options.setBootClassPath(ImmutableList.copyOf(entries.map(_.toString).asJava))
    }

    val release = releaseOverride.orElse(if (entries.isEmpty) Some("8") else None)
    release.foreach { value =>
      options.setLanguageVersion(LanguageVersion.fromJavacopts(ImmutableList.of("--release", value)))
    }
  }

  private final case class CommandResult(exitCode: Int, stdout: String, stderr: String)

  private def runCommand(cmd: Seq[String], cwd: Path): CommandResult = {
    val out = new StringBuilder
    val err = new StringBuilder
    val exit = Process(cmd, cwd.toFile).!(
      ProcessLogger(
        line => out.append(line).append('\n'),
        line => err.append(line).append('\n'),
      )
    )
    CommandResult(exit, out.toString, err.toString)
  }

  private object BuildConfigJson {
    private val gson = new GsonBuilder().setPrettyPrinting().create()

    def write(path: Path, config: BuildConfig): Either[String, Unit] = {
      Try {
        Files.createDirectories(path.getParent)
        val json = toJson(config)
        Files.writeString(path, gson.toJson(json))
      }.toEither.left.map(e => s"Failed to write JSON: ${e.getMessage}")
    }

    def read(path: Path): Either[String, BuildConfig] = {
      if (!Files.isRegularFile(path)) {
        return Left(s"Build config JSON not found: $path")
      }
      Try {
        val json = JsonParser.parseString(Files.readString(path)).getAsJsonObject
        val dependencyModules = readDependencyModules(json)
        val classpath = readStringArray(json, "classpath")
        val sourcepaths = readStringArray(json, "sourcepaths")
        val generatedSourceDirs = readStringArray(json, "generatedSourceDirs")
        val outputDirs = readStringArray(json, "outputDirs")
        val scalaVersion = readString(json, "scalaVersion").getOrElse("2.13")
        val buildTool = readString(json, "buildTool").getOrElse("unknown")
        val workspace = readString(json, "workspace").getOrElse("")
        val generatedAt = readString(json, "generatedAt").getOrElse("")

        if (classpath.isEmpty || sourcepaths.isEmpty || outputDirs.isEmpty) {
          throw new IllegalArgumentException(
            "Build config missing classpath/sourcepaths/outputDirs; re-run export"
          )
        }

        BuildConfig(
          dependencyModules = dependencyModules,
          classpath = classpath,
          sourcepaths = sourcepaths,
          generatedSourceDirs = generatedSourceDirs,
          outputDirs = outputDirs,
          scalaVersion = scalaVersion,
          buildTool = buildTool,
          workspace = workspace,
          generatedAt = generatedAt,
        )
      }.toEither.left.map(e => s"Failed to read JSON: ${e.getMessage}")
    }

    private def toJson(config: BuildConfig): JsonObject = {
      val root = new JsonObject

      val deps = new JsonArray
      config.dependencyModules.foreach { module =>
        val obj = new JsonObject
        obj.addProperty("id", module.id)
        obj.addProperty("jar", module.jar)
        module.sources.foreach(sources => obj.addProperty("sources", sources))
        deps.add(obj)
      }
      root.add("dependencyModules", deps)

      addStringArray(root, "classpath", config.classpath)
      addStringArray(root, "sourcepaths", config.sourcepaths)
      addStringArray(root, "generatedSourceDirs", config.generatedSourceDirs)
      addStringArray(root, "outputDirs", config.outputDirs)
      root.addProperty("scalaVersion", config.scalaVersion)
      root.addProperty("buildTool", config.buildTool)
      root.addProperty("workspace", config.workspace)
      root.addProperty("generatedAt", config.generatedAt)

      root
    }

    private def addStringArray(root: JsonObject, name: String, values: Seq[String]): Unit = {
      val arr = new JsonArray
      values.foreach(arr.add)
      root.add(name, arr)
    }

    private def readStringArray(json: JsonObject, name: String): Seq[String] = {
      Option(json.getAsJsonArray(name))
        .map(_.asScala.toSeq.map(_.getAsString))
        .getOrElse(Nil)
    }

    private def readString(json: JsonObject, name: String): Option[String] = {
      Option(json.get(name)).map(_.getAsString)
    }

    private def readDependencyModules(json: JsonObject): Seq[DependencyModule] = {
      Option(json.getAsJsonArray("dependencyModules"))
        .map(_.asScala.toSeq.flatMap { element =>
          val obj = element.getAsJsonObject
          val id = Option(obj.get("id")).map(_.getAsString)
          val jar = Option(obj.get("jar")).map(_.getAsString)
          (id, jar) match {
            case (Some(mid), Some(mjar)) =>
              val sources = Option(obj.get("sources")).map(_.getAsString)
              Some(DependencyModule(mid, mjar, sources))
            case _ => None
          }
        })
        .getOrElse(Nil)
    }
  }

  private def distinctPaths(paths: Seq[Path]): Seq[Path] = {
    val seen = mutable.LinkedHashSet.empty[String]
    val out = mutable.ListBuffer.empty[Path]
    paths.foreach { path =>
      val normalized = path.toAbsolutePath.normalize
      val key = normalized.toString
      if (seen.add(key)) {
        out += normalized
      }
    }
    out.toSeq
  }

  private def distinctStrings(values: Seq[String]): Seq[String] = {
    val seen = mutable.LinkedHashSet.empty[String]
    val out = mutable.ListBuffer.empty[String]
    values.foreach { value =>
      if (seen.add(value)) {
        out += value
      }
    }
    out.toSeq
  }

  private def filterByScalaVersion(
      modules: Seq[DependencyModule],
      scalaVersion: String,
  ): Seq[DependencyModule] = {
    val excludeSuffixes = scalaVersion match {
      case v if v.startsWith("2.12") => Seq("_2.13", "_3")
      case v if v.startsWith("2.13") => Seq("_2.12", "_3")
      case v if v.startsWith("3") => Seq("_2.12", "_2.13")
      case _ => Seq.empty
    }

    modules.filter { module =>
      val artifactId = module.id.split(":").lift(1).getOrElse("")
      val hasScalaSuffix = artifactId.matches(".*_\\d+(\\.\\d+)?$") || artifactId.endsWith("_3")

      if (!hasScalaSuffix) {
        true
      } else {
        !excludeSuffixes.exists(suffix => artifactId.endsWith(suffix))
      }
    }
  }

  private def log(config: Config, message: String): Unit = {
    if (config.verbose) {
      println(message)
    }
  }
}
