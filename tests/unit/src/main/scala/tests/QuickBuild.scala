package tests

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest

import scala.util.Properties
import scala.util.matching.Regex

import scala.meta.internal.io.FileIO
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ScalaVersions
import scala.meta.internal.metals.Time
import scala.meta.internal.metals.Timer
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.internal.mtags.MD5
import scala.meta.io.AbsolutePath

import bloop.config.Config
import bloop.config.Tag
import bloop.config.{Config => C}
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import coursierapi.Dependency
import coursierapi.Fetch
import coursierapi.MavenRepository
import coursierapi.Repository

/**
 * A basic build tool for faster testing.
 *
 * Spending 30 seconds for every `sbt bloopInstall` to run a basic test
 * makes it annoying to work on the Metals codebase because tests will be too slow.
 * QuickBuild is a basic build tool build on top of coursier+bloop and
 * generates Bloop JSON files in a few seconds (when artifacts are cached)
 * compared to 30s with sbt. The speedups are significant when you have multiple
 * test cases:
 *
 * - Time to run 5 sbt integration tests: 136s
 * - Time to run 5 metals.json integration tests: 12s
 *
 * A build is declared in metals.json and looks like this: {{{
 *   {
 *     "id": {
 *       "scalaVersion": "2.12.11",
 *       "libraryDependencies": [
 *         "org.scalatest::scalatest:3.0.5",
 *       ],
 *       "compilerPlugins": [
 *         "org.scalameta:::semanticdb-scalac:4.0.0"
 *       ],
 *       scalacOptions: [
 *         "-deprecation"
 *       ],
 *       dependsOn: [ "id2" ]
 *     },
 *    "id2": { ... }
 *   }
 * }}}
 */
case class QuickBuild(
    id: String,
    scalaVersion: String,
    libraryDependencies: Array[String],
    compilerPlugins: Array[String],
    scalacOptions: Array[String],
    dependsOn: Array[String],
    additionalSources: Array[String],
    sbtVersion: String,
    sbtAutoImports: Array[String],
    platformJavaHome: String,
) {
  def withId(id: String): QuickBuild =
    QuickBuild(
      id,
      if (scalaVersion == null) V.scala213
      else scalaVersion,
      orEmpty(libraryDependencies),
      orEmpty(compilerPlugins),
      orEmpty(scalacOptions),
      orEmpty(dependsOn),
      orEmpty(additionalSources),
      sbtVersion,
      orEmpty(sbtAutoImports),
      platformJavaHome,
    )
  private def orEmpty(array: Array[String]): Array[String] =
    if (array == null) new Array(0) else array
  def scalaBinaryVersion: String =
    ScalaVersions.scalaBinaryVersionFromFullVersion(scalaVersion)
  def toBloop(workspace: AbsolutePath): C.Project = {
    val testFrameworks = {
      val frameworks = libraryDependencies
        .map(lib => lib.take(lib.lastIndexOf(":")))
        .flatMap(QuickBuild.supportedTestFrameworks.get)
        .toList

      if (frameworks.isEmpty) None
      else Some(Config.Test(frameworks, Config.TestOptions.empty))
    }
    val baseDirectory: Path = workspace.resolve(id).toNIO
    val binaryVersion: String = scalaBinaryVersion
    val out: Path = workspace.resolve(".bloop").resolve(id).toNIO
    val isTest = testFrameworks.nonEmpty
    val classDirectory: Path = {
      val testPrefix = if (isTest) "test-" else ""
      out
        .resolve(s"scala-$binaryVersion")
        .resolve(s"${testPrefix}classes")
    }
    val extraSources =
      additionalSources.map(relpath => workspace.resolve(relpath).toNIO).toList
    val sources = extraSources ::: List(
      "src/main/java",
      "src/main/scala",
      s"src/main/scala-$binaryVersion",
      s"src/main/scala-$binaryVersion",
    ).map(relpath => baseDirectory.resolve(relpath))
    val scalaDependencies =
      if (ScalaVersions.isScala3Version(scalaVersion)) {
        Array(
          s"org.scala-lang:scala3-library_$binaryVersion:$scalaVersion"
        )
      } else {
        Array(
          s"org.scala-lang:scala-library:$scalaVersion",
          s"org.scala-lang:scala-reflect:$scalaVersion",
        )
      }
    val allDependencies = scalaDependencies ++ libraryDependencies
    val allJars = QuickBuild.fetch(
      allDependencies,
      scalaVersion,
      binaryVersion,
      sources = true,
    )
    def isSourceJar(jarFile: Path): Boolean = {
      jarFile.getFileName.toString.endsWith("-sources.jar")
    }
    val classpath = classDirectory :: allJars.filterNot(isSourceJar)
    val allPlugins =
      if (
        ScalaVersions.isSupportedAtReleaseMomentScalaVersion(
          scalaVersion
        ) && !ScalaVersions.isScala3Version(scalaVersion)
      ) {
        val semanticdbVersion =
          BuildInfoVersions.lastSupportedSemanticdb.getOrElse(
            scalaVersion,
            V.scalametaVersion,
          )
        s"org.scalameta:::semanticdb-scalac:$semanticdbVersion" :: compilerPlugins.toList
      } else compilerPlugins.toList
    val pluginDependencies = allPlugins.map(plugin =>
      QuickBuild
        .toDependency(plugin, scalaVersion, binaryVersion)
        .withTransitive(false)
    )
    val pluginJars = QuickBuild.fetchDependencies(pluginDependencies)
    val plugins = pluginJars.map(jar => s"-Xplugin:$jar")
    val allScalacOptions =
      if (ScalaVersions.isScala3Version(scalaVersion)) {
        scalacOptions.toList ++ plugins
      } else {
        val cache =
          if (scalaVersion == V.scala213)
            List("-Ycache-plugin-class-loader:last-modified")
          else List()
        List(
          List(
            "-Yrangepos",
            s"-Xplugin-require:semanticdb",
            s"-P:semanticdb:failures:warning",
            s"-P:semanticdb:synthetics:on",
            s"-P:semanticdb:sourceroot:$workspace",
            s"-P:semanticdb:targetroot:$classDirectory",
          ),
          plugins,
          cache,
          scalacOptions.toList,
        ).flatten
      }
    def artifactName(jarFile: Path): String = {
      jarFile.getFileName.toString.stripSuffix(".jar").stripSuffix("-sources")
    }
    val resolution = allJars
      .groupBy(artifactName)
      .map { case (name, jars) =>
        val artifacts = jars.map { jar =>
          val classifier = if (isSourceJar(jar)) Some("sources") else None
          C.Artifact(
            name,
            classifier,
            checksum = None,
            jar,
          )
        }
        C.Module(
          organization = "",
          name,
          version = "",
          configurations = None,
          artifacts,
        )
      }
    val javaHome = Option(platformJavaHome)
      .map(Paths.get(_))
      .orElse(Option(Properties.jdkHome).map(Paths.get(_)))

    val tags = if (isTest) Tag.Test :: Nil else Nil

    val scalaCompiler =
      if (ScalaVersions.isScala3Version(scalaVersion))
        s"org.scala-lang:scala3-compiler_$binaryVersion:$scalaVersion"
      else s"org.scala-lang:scala-compiler:$scalaVersion"
    val scalaOrg = "org.scala-lang"
    val scalaCompilerName =
      if (ScalaVersions.isScala3Version(scalaVersion))
        s"scala3-compiler_$binaryVersion"
      else s"scala-compiler"

    val sbt = Option(sbtVersion).map { version =>
      C.Sbt(version, sbtAutoImports.toList)
    }

    C.Project(
      id,
      baseDirectory,
      Some(workspace.toNIO),
      sources,
      None,
      None,
      dependsOn.toList,
      classpath,
      out,
      classDirectory,
      scala = Some(
        C.Scala(
          scalaOrg,
          scalaCompilerName,
          scalaVersion,
          allScalacOptions,
          QuickBuild.fetch(
            Array(
              scalaCompiler,
              "jline:jline:2.14.6",
            ),
            scalaVersion,
            binaryVersion,
          ),
          None,
          setup = Some(
            C.CompileSetup(
              C.Mixed,
              addLibraryToBootClasspath = true,
              addCompilerToClasspath = false,
              addExtraJarsToClasspath = false,
              manageBootClasspath = true,
              filterLibraryFromClasspath = true,
            )
          ),
          None,
        )
      ),
      java = Some(C.Java(Nil)),
      sbt = sbt,
      test = testFrameworks,
      platform = Some(
        C.Platform.Jvm(C.JvmConfig(javaHome, Nil), None, None, None, None)
      ),
      resolution = Some(C.Resolution(resolution.toList)),
      resources = None,
      tags = Some(tags),
      sourceGenerators = None,
    )
  }
}

object QuickBuild {
  val supportedTestFrameworks: Map[String, C.TestFramework] = Map(
    "org.scalatest::scalatest" -> Config.TestFramework.ScalaTest,
    "com.lihaoyi::utest" -> Config.TestFramework(
      List("utest.runner.Framework")
    ),
    "org.scalameta::munit" -> Config.TestFramework.munit,
    "junit:junit" -> Config.TestFramework.JUnit,
    "com.disneystreaming::weaver-cats" -> Config.TestFramework(
      List("weaver.framework.CatsEffect")
    ),
  )

  /**
   * Bump up this version in case the JSON generation algorithm changes
   * A new version triggers re-generation of QuickBuild files.
   */
  val version = "v3"
  def toDependency(
      module: String,
      scalaVersion: String,
      scalaBinaryVersion: String,
  ): Dependency =
    module match {
      case Full(org, name, version) =>
        Dependency.of(org, s"${name}_$scalaVersion", version)
      case Half(org, name, version) =>
        Dependency.of(org, s"${name}_$scalaBinaryVersion", version)
      case Java(org, name, version) =>
        Dependency.of(org, name, version)
      case _ =>
        throw new IllegalArgumentException(module)
    }
  def fetch(
      dependencies: Array[String],
      scalaVersion: String,
      scalaBinaryVersion: String,
      sources: Boolean = false,
  ): List[Path] =
    fetchDependencies(
      dependencies.iterator
        .map(d => toDependency(d, scalaVersion, scalaBinaryVersion))
        .toList,
      sources,
    )
  def fetchDependencies(
      dependencies: List[Dependency],
      sources: Boolean = false,
  ): List[Path] = {
    val classifiers =
      if (sources) Set("sources")
      else Set.empty[String]

    val repositories =
      Repository.defaults().asScala ++
        List(
          Repository.central(),
          Repository.ivy2Local(),
          MavenRepository.of(
            "https://oss.sonatype.org/content/repositories/public"
          ),
        )

    Fetch
      .create()
      .withRepositories(repositories.toSeq: _*)
      .withDependencies(dependencies: _*)
      .withClassifiers(classifiers.asJava)
      .withMainArtifacts()
      .fetch()
      .map(_.toPath)
      .asScala
      .toList
  }

  val Full: Regex = "(.+):::(.+):(.+)".r
  val Half: Regex = "(.+)::(.+):(.+)".r
  val Java: Regex = "(.+):(.+):(.+)".r
  def parseJson(text: String): JsonObject = {
    JsonParser.parseString(text).getAsJsonObject()
  }

  def newDigest(workspace: AbsolutePath): Option[(AbsolutePath, String)] = {

    val digestFile =
      workspace.resolve(".metals").resolve("quick-build.md5")
    val oldDigest =
      if (digestFile.isFile) FileIO.slurp(digestFile, StandardCharsets.UTF_8)
      else "unknown"
    val newDigest = {
      val digest = MessageDigest.getInstance("MD5")
      digest.update(version.getBytes(StandardCharsets.UTF_8))
      digest.update(V.scala213.getBytes(StandardCharsets.UTF_8))
      def update(file: AbsolutePath): Unit = {
        if (file.isFile) {
          digest.update(file.readAllBytes)
        }
      }
      update(workspace.resolve("metals.json"))
      val bloopDirectory = workspace.resolve(".bloop").toNIO
      Files.createDirectories(bloopDirectory)
      AbsolutePath(bloopDirectory).list
        .filter(_.extension == "json")
        .foreach(json => update(json))
      MD5.bytesToHex(digest.digest())
    }
    if (oldDigest == newDigest) None
    else Some(digestFile -> newDigest)
  }

  def bloopInstall(workspace: AbsolutePath): Unit = {
    val json = workspace.resolve("metals.json")
    if (json.isFile) {
      newDigest(workspace) match {
        case None =>
        case Some((digestFile, digest)) =>
          val timer = new Timer(Time.system)
          val gson = new Gson()
          val text = FileIO.slurp(json, StandardCharsets.UTF_8)
          val obj = parseJson(text)
          val projects = obj.entrySet().asScala.map { entry =>
            val project =
              gson.fromJson[QuickBuild](entry.getValue, classOf[QuickBuild])
            project.withId(entry.getKey)
          }
          val bloopDirectory = workspace.resolve(".bloop").toNIO
          Files.createDirectories(bloopDirectory)
          AbsolutePath(bloopDirectory).list
            .filter(_.extension == "json")
            .foreach(json => json.delete())
          val bloopProjects = projects.map(_.toBloop(workspace))
          val byName = bloopProjects.map(p => p.name -> p).toMap
          val fullClasspathProjects = bloopProjects.map { p =>
            val fullClasspath = p.dependencies.flatMap { d =>
              byName(d).classpath
            }
            p.copy(
              classpath = (p.classpath ++ fullClasspath).distinct
            )
          }
          fullClasspathProjects.foreach { project =>
            val out = bloopDirectory.resolve(project.name + ".json")
            bloop.config.write(C.File(V.bloopVersion, project), out)
          }
          Files.createDirectories(digestFile.toNIO.getParent)
          Files.write(digestFile.toNIO, digest.getBytes(StandardCharsets.UTF_8))
          scribe.info(s"time: generated quick build in $timer")
      }
    }
  }
}
