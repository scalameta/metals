import scala.collection.mutable
import scala.sys.process._
import Developers._
import Tests._

Global / onChangedBuildSource := ReloadOnSourceChanges

// For testing nightlies
Global / resolvers += "scala-integration" at
  "https://scala-ci.typesafe.com/artifactory/scala-integration/"

def localSnapshotVersion = "0.11.10-SNAPSHOT"
def isCI = System.getenv("CI") != null

def isScala211(v: Option[(Long, Long)]): Boolean = v.contains((2, 11))
def isScala212(v: Option[(Long, Long)]): Boolean = v.contains((2, 12))
def isScala213(v: Option[(Long, Long)]): Boolean = v.contains((2, 13))
def isScala2(v: Option[(Long, Long)]): Boolean = v.exists(_._1 == 2)
def isScala3(v: Option[(Long, Long)]): Boolean = v.exists(_._1 == 3)

def crossSetting[A](
    scalaVersion: String,
    if211: List[A] = Nil,
    if213: List[A] = Nil,
    if3: List[A] = Nil,
    if2: List[A] = Nil,
): List[A] =
  CrossVersion.partialVersion(scalaVersion) match {
    case partialVersion if isScala211(partialVersion) => if211 ::: if2
    case partialVersion if isScala212(partialVersion) => if2
    case partialVersion if isScala213(partialVersion) => if2 ::: if213
    case partialVersion if isScala3(partialVersion) => if3
    case _ => Nil
  }

logo := Welcome.logo
usefulTasks := Welcome.tasks

ThisBuild / scalafixScalaBinaryVersion := scalaBinaryVersion.value

inThisBuild(
  List(
    version ~= { dynVer =>
      if (isCI) dynVer
      else localSnapshotVersion // only for local publishing
    },
    scalaVersion := V.scala213,
    crossScalaVersions := List(V.scala213),
    scalacOptions ++= List(
      "-target:jvm-1.8",
      "-Yrangepos",
    ),
    scalafixDependencies += "com.github.liancheng" %% "organize-imports" % V.organizeImportRule,
    organization := "org.scalameta",
    licenses := Seq(
      "Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")
    ),
    homepage := Some(url("https://github.com/scalameta/metals")),
    developers := metalsDevs,
    testFrameworks := List(),
    resolvers ++= Resolver.sonatypeOssRepos("public"),
    resolvers ++= Resolver.sonatypeOssRepos("snapshot"),
    dependencyOverrides += V.guava,
    // faster publishLocal:
    packageDoc / publishArtifact := sys.env.contains("CI"),
    packageSrc / publishArtifact := sys.env.contains("CI"),
    resolvers += Resolver.bintrayRepo("scalacenter", "releases"),
  )
)

Global / onLoad ~= { old =>
  if (!scala.util.Properties.isWin) {
    import java.nio.file._
    val prePush = Paths.get(".git", "hooks", "pre-push")
    Files.createDirectories(prePush.getParent)
    Files.write(
      prePush,
      """#!/bin/sh
        |set -eux
        |bin/scalafmt --diff --diff-branch main
        |git diff --exit-code
        |""".stripMargin.getBytes(),
    )
    prePush.toFile.setExecutable(true)
  }
  old
}
Global / cancelable := true
Global / excludeLintKeys += scalafixConfig
crossScalaVersions := Nil

addCommandAlias("scalafixAll", "all compile:scalafix test:scalafix")
addCommandAlias("scalafixCheck", "; scalafix --check ; test:scalafix --check")
addCommandAlias(
  "downloadDependencies",
  "+publishLocal; metals/runMain scala.meta.metals.DownloadDependencies ",
)

def configureMtagsScalaVersionDynamically(
    state: State,
    scalaV: String,
): State = {
  val scalaVersionSettings =
    List(
      mtest / scalaVersion := scalaV,
      mtags / scalaVersion := scalaV,
      cross / scalaVersion := scalaV,
    )
  val extracted = Project.extract(state)
  extracted
    .appendWithSession(
      scalaVersionSettings,
      state,
    )
}

def crossTestDyn(state: State, scalaV: String): State = {
  val configured = configureMtagsScalaVersionDynamically(state, scalaV)
  val (out, _) =
    Project
      .extract(configured)
      .runTask(cross / Test / test, configured)
  out
}

commands ++= Seq(
  Command.command("save-expect") { s =>
    "unit/test:runMain tests.SaveExpect" :: s
  },
  Command.command("quick-publish-local") { s =>
    val publishMtags = V.quickPublishScalaVersions.foldLeft(s) { case (st, v) =>
      runMtagsPublishLocal(st, v, localSnapshotVersion)
    }
    "interfaces/publishLocal" :: s"++${V.scala213} metals/publishLocal" :: publishMtags
  },
  Command.command("cross-test-latest-nightly") { s =>
    val max =
      if (V.nightlyScala3DottyVersions.nonEmpty)
        Option(V.nightlyScala3DottyVersions.max)
      else None
    max match {
      case Some(latest) => crossTestDyn(s, latest.toString)
      case None =>
        println("No nightly versions was found. Skipping cross/test")
        s
    }
  },
  Command.single("test-mtags-dyn") { (s, scalaV) =>
    crossTestDyn(s, scalaV)
  },
  // this is one is needed for `.github/workflows/check_scala3_nightly`
  Command.single("save-non-published-nightlies") { (s, path) =>
    val versions = Scala3NightlyVersions.nonPublishedNightlyVersions
    IO.write(file(path), versions.map(_.original).mkString("\n"))
    s
  },
)

// -Xlint is unusable because of
// https://github.com/scala/bug/issues/10448
def lintingOptions(scalaVersion: String) = {
  val unused213 = "-Wunused"
  val unused3 = "-Wunused:all"
  val common = List(
    // desugaring of for yield caused pattern var to complain
    // https://github.com/scala/bug/issues/10287
    "-Wconf:msg=parameter value .+ in anonymous function:silent",
    // silence unused parameters in mtags
    "-Wconf:src=*.ScaladocParser.scala&msg=parameter value (pos|message) in method reportError:silent",
    "-Wconf:src=*.Completions.scala&msg=parameter value (member|m) in method (isCandidate|isPrioritized):silent",
    "-Wconf:src=*.JavaMtags.scala&msg=parameter value (ctor|method) in method (visitConstructor|visitMethod):silent",
    "-Wconf:src=*.MtagsIndexer.scala&msg=parameter value owner in method visitOccurrence:silent",
    // silence "The outer reference in this type test cannot be checked at run time."
    "-Wconf:src=.*(CompletionProvider|ArgCompletions|Completions|Keywords|IndentOnPaste).scala&msg=The outer reference:silent",
    "-Wconf:src=*.BasePCSuite.scala&msg=parameter value (scalaVersion|classpath) in method (extraDependencies|scalacOptions):silent",
  )
  // -Wconf is available only from 2.13.2
  val commonFiltered =
    if (scalaVersion == "2.13.1") common.filterNot(_.startsWith("-Wconf"))
    else common
  crossSetting(
    scalaVersion,
    if213 = unused213 :: commonFiltered,
    if3 = unused3 :: Nil,
    if211 = List("-Ywarn-unused-import"),
  )
}

val sharedJavacOptions = List(
  Compile / javacOptions ++= {
    if (sys.props("java.version").startsWith("1.8"))
      Nil
    else
      Seq("--release", "8")
  }
)

val sharedSettings = sharedJavacOptions ++ List(
  libraryDependencies ++= crossSetting(
    scalaVersion.value,
    if2 = List(
      compilerPlugin(
        "org.scalameta" % "semanticdb-scalac" % V.scalameta cross CrossVersion.full
      )
    ),
  ),
  scalacOptions ++= crossSetting(
    scalaVersion.value,
    if3 = List(
      "-language:implicitConversions",
      "-Xtarget:8",
      "-Xsemanticdb",
    ),
    if211 = List("-Xexperimental"),
  ),
  scalacOptions --= crossSetting(
    scalaVersion.value,
    if3 = List("-Yrangepos", "-target:jvm-1.8"),
  ),
  scalacOptions ++= lintingOptions(scalaVersion.value),
)

publish / skip := true

lazy val interfaces = project
  .in(file("mtags-interfaces"))
  .settings(sharedJavacOptions)
  .settings(
    moduleName := "mtags-interfaces",
    autoScalaLibrary := false,
    crossPaths := false,
    libraryDependencies ++= List(
      V.lsp4j
    ),
    crossVersion := CrossVersion.disabled,
    Compile / doc / javacOptions ++= List(
      "-tag",
      "implNote:a:Implementation Note:",
    ),
  )

def multiScalaDirectories(root: File, scalaVersion: String) = {
  val base = root / "src" / "main"
  val result = mutable.ListBuffer.empty[File]
  val partialVersion = CrossVersion.partialVersion(scalaVersion)
  partialVersion.collect { case (major, minor) =>
    result += base / s"scala-$major.$minor"
  }
  if (isScala2(partialVersion)) {
    result += base / "scala-2"
  }
  if (isScala3(partialVersion)) {
    result += base / "scala-3"
  }
  result += base / s"scala-$scalaVersion"
  result.toList
}

val mtagsSettings = List(
  crossScalaVersions := {
    V.supportedScalaVersions ++ V.nightlyScala3Versions
  },
  crossTarget := target.value / s"scala-${scalaVersion.value}",
  crossVersion := CrossVersion.full,
  Compile / unmanagedSourceDirectories ++= multiScalaDirectories(
    (ThisBuild / baseDirectory).value / "mtags",
    scalaVersion.value,
  ),
  // @note needed to deal with issues with dottyDoc
  Compile / doc / sources := Seq.empty,
  libraryDependencies ++= Seq(
    "com.lihaoyi" %% "geny" % V.genyVersion,
    "com.thoughtworks.qdox" % "qdox" % V.qdox, // for java mtags
    "org.scala-lang.modules" %% "scala-java8-compat" % V.java8Compat,
    "org.jsoup" % "jsoup" % V.jsoup, // for extracting HTML from javadocs
    // for ivy completions
    "io.get-coursier" % "interface" % V.coursierInterfaces,
  ),
  libraryDependencies ++= crossSetting(
    scalaVersion.value,
    if2 = List(
      // for token edit-distance used by goto definition
      "com.googlecode.java-diff-utils" % "diffutils" % "1.3.0",
      "org.scalameta" % "semanticdb-scalac-core" % V.scalameta cross CrossVersion.full,
    ),
    if3 = List(
      "org.scala-lang" %% "scala3-compiler" % scalaVersion.value,
      ("org.scalameta" %% "scalameta" % V.scalameta)
        .cross(CrossVersion.for3Use2_13)
        .exclude("org.scala-lang", "scala-reflect")
        .exclude("org.scala-lang", "scala-compiler")
        .exclude(
          "com.lihaoyi",
          "geny_2.13",
        ) // avoid 2.13 and 3 on the classpath since we rely on it directly
        .exclude(
          "com.lihaoyi",
          "sourcecode_2.13",
        ), // avoid 2.13 and 3 on the classpath since it comes in via pprint
    ),
  ),
  libraryDependencies ++= List("org.lz4" % "lz4-java" % "1.8.0"),
  libraryDependencies ++= {
    if (isCI) Nil
    // NOTE(olafur) pprint is indispensable for me while developing, I can't
    // use println anymore for debugging because pprint.log is 100 times better.
    else {
      List("com.lihaoyi" %% "pprint" % V.pprint)
    }
  },
  buildInfoPackage := "scala.meta.internal.mtags",
  buildInfoKeys := Seq[BuildInfoKey](
    "scalaCompilerVersion" -> scalaVersion.value
  ),
  Compile / unmanagedSourceDirectories := {
    val current = (Compile / unmanagedSourceDirectories).value
    val base = (Compile / sourceDirectory).value
    val regex = "(\\d+)\\.(\\d+)\\.(\\d+).*".r
    // For scala 2.13.9/10 we need to have a special Compat.scala
    // For this case filter out `scala-2.13` directory that comes by default
    if (scalaVersion.value == "2.13.9" || scalaVersion.value == "2.13.10")
      current.filter(f => f.getName() != "scala-2.13")
    else
      current
  },
)

lazy val mtags3 = project
  .in(file(".mtags"))
  .settings(
    Compile / unmanagedSourceDirectories := Seq(),
    sharedSettings,
    mtagsSettings,
    Compile / unmanagedSourceDirectories += (ThisBuild / baseDirectory).value / "mtags" / "src" / "main" / "scala",
    moduleName := "mtags3",
    scalaVersion := V.scala3,
    target := (ThisBuild / baseDirectory).value / "mtags" / "target" / "target3",
    publish / skip := true,
    scalafixConfig := Some(
      (ThisBuild / baseDirectory).value / ".scalafix3.conf"
    ),
  )
  .dependsOn(interfaces)
  .enablePlugins(BuildInfoPlugin)

lazy val mtags = project
  .settings(
    sharedSettings,
    mtagsSettings,
    moduleName := "mtags",
  )
  .dependsOn(interfaces)
  .enablePlugins(BuildInfoPlugin)

lazy val metals = project
  .settings(
    sharedSettings,
    Compile / run / fork := true,
    Compile / mainClass := Some("scala.meta.metals.Main"),
    // As a general rule of thumb, we try to keep Scala dependencies to a minimum.
    libraryDependencies ++= List(
      // =================
      // Java dependencies
      // =================
      // for bloom filters
      V.guava,
      "com.geirsson" %% "metaconfig-core" % "0.11.1",
      // for measuring memory footprint
      "org.openjdk.jol" % "jol-core" % "0.16",
      // for file watching
      "com.swoval" % "file-tree-views" % "2.1.9",
      // for http client
      "io.undertow" % "undertow-core" % "2.2.20.Final",
      "org.jboss.xnio" % "xnio-nio" % "3.8.8.Final",
      // for persistent data like "dismissed notification"
      "org.flywaydb" % "flyway-core" % "9.8.3",
      "com.h2database" % "h2" % "2.1.214",
      // for BSP
      "org.scala-sbt.ipcsocket" % "ipcsocket" % "1.5.0",
      "ch.epfl.scala" % "bsp4j" % V.bsp,
      "ch.epfl.scala" %% "bloop-launcher" % V.bloopNightly,
      // for LSP
      V.lsp4j,
      // for DAP
      V.dap4j,
      // for finding paths of global log/cache directories
      "dev.dirs" % "directories" % "26",
      // for Java formatting
      "org.eclipse.jdt" % "org.eclipse.jdt.core" % "3.25.0" exclude ("*", "*"),
      "org.eclipse.platform" % "org.eclipse.ant.core" % "3.5.500" exclude ("*", "*"),
      "org.eclipse.platform" % "org.eclipse.compare.core" % "3.6.600" exclude ("*", "*"),
      "org.eclipse.platform" % "org.eclipse.core.commands" % "3.9.500" exclude ("*", "*"),
      "org.eclipse.platform" % "org.eclipse.core.contenttype" % "3.7.500" exclude ("*", "*"),
      "org.eclipse.platform" % "org.eclipse.core.expressions" % "3.6.500" exclude ("*", "*"),
      "org.eclipse.platform" % "org.eclipse.core.filesystem" % "1.7.500" exclude ("*", "*"),
      "org.eclipse.platform" % "org.eclipse.core.jobs" % "3.10.500" exclude ("*", "*"),
      "org.eclipse.platform" % "org.eclipse.core.resources" % "3.13.500" exclude ("*", "*"),
      "org.eclipse.platform" % "org.eclipse.core.runtime" % "3.16.0" exclude ("*", "*"),
      "org.eclipse.platform" % "org.eclipse.core.variables" % "3.4.600" exclude ("*", "*"),
      "org.eclipse.platform" % "org.eclipse.equinox.app" % "1.4.300" exclude ("*", "*"),
      "org.eclipse.platform" % "org.eclipse.equinox.common" % "3.10.600" exclude ("*", "*"),
      "org.eclipse.platform" % "org.eclipse.equinox.preferences" % "3.7.600" exclude ("*", "*"),
      "org.eclipse.platform" % "org.eclipse.equinox.registry" % "3.8.600" exclude ("*", "*"),
      "org.eclipse.platform" % "org.eclipse.osgi" % "3.15.0" exclude ("*", "*"),
      "org.eclipse.platform" % "org.eclipse.team.core" % "3.8.700" exclude ("*", "*"),
      "org.eclipse.platform" % "org.eclipse.text" % "3.9.0" exclude ("*", "*"),
      // ==================
      // Scala dependencies
      // ==================
      "org.scalameta" % "mdoc-interfaces" % V.mdoc,
      "org.scalameta" %% "scalafmt-dynamic" % V.scalafmt,
      "ch.epfl.scala" % "scalafix-interfaces" % V.scalafix,
      // For reading classpaths.
      // for fetching ch.epfl.scala:bloop-frontend and other library dependencies
      "io.get-coursier" % "interface" % V.coursierInterfaces,
      // for comparing versions
      "io.get-coursier" %% "versions" % "0.3.1",
      // for logging
      "com.outr" %% "scribe" % V.scribe,
      "com.outr" %% "scribe-file" % V.scribe,
      "com.outr" %% "scribe-slf4j" % V.scribe, // needed for flyway database migrations
      // for JSON formatted doctor
      "com.lihaoyi" %% "ujson" % "2.0.0",
      // For remote language server
      "com.lihaoyi" %% "requests" % "0.7.1",
      // for producing SemanticDB from Scala source files
      "org.scalameta" %% "scalameta" % V.scalameta,
      "org.scalameta" % "semanticdb-scalac-core" % V.scalameta cross CrossVersion.full,
      // For starting Ammonite
      "io.github.alexarchambault.ammonite" %% "ammonite-runner" % "0.4.0",
      "org.scala-lang.modules" %% "scala-xml" % "2.1.0",
      "org.scala-lang.modules" %% "scala-parallel-collections" % "1.0.4",
      ("org.virtuslab.scala-cli" % "scala-cli-bsp" % V.scalaCli)
        .exclude("ch.epfl.scala", "bsp4j"),
    ),
    buildInfoPackage := "scala.meta.internal.metals",
    buildInfoKeys := Seq[BuildInfoKey](
      "localSnapshotVersion" -> localSnapshotVersion,
      "metalsVersion" -> version.value,
      "mdocVersion" -> V.mdoc,
      "bspVersion" -> V.bsp,
      "sbtVersion" -> sbtVersion.value,
      "bloopVersion" -> V.bloop,
      "bloopNightlyVersion" -> V.bloop,
      "sbtBloopVersion" -> V.sbtBloop,
      "gradleBloopVersion" -> V.gradleBloop,
      "mavenBloopVersion" -> V.mavenBloop,
      "scalametaVersion" -> V.scalameta,
      "semanticdbVersion" -> V.semanticdb,
      "javaSemanticdbVersion" -> V.javaSemanticdb,
      "scalafmtVersion" -> V.scalafmt,
      "ammoniteVersion" -> V.ammonite,
      "scalaCliVersion" -> V.scalaCli,
      "organizeImportVersion" -> V.organizeImportRule,
      "millVersion" -> V.mill,
      "debugAdapterVersion" -> V.debugAdapter,
      "sbtJdiToolsVersion" -> V.sbtJdiTools,
      "supportedScalaVersions" -> V.supportedScalaVersions,
      "supportedScala2Versions" -> V.scala2Versions,
      "minimumSupportedSbtVersion" -> V.minimumSupportedSbtVersion,
      "supportedScala3Versions" -> V.scala3Versions,
      "supportedScalaBinaryVersions" -> V.supportedScalaBinaryVersions,
      "deprecatedScalaVersions" -> V.deprecatedScalaVersions,
      "nonDeprecatedScalaVersions" -> V.nonDeprecatedScalaVersions,
      "scala211" -> V.scala211,
      "scala212" -> V.scala212,
      "ammonite212" -> V.ammonite212Version,
      "ammonite213" -> V.ammonite213Version,
      "ammonite3" -> V.ammonite3Version,
      "scala213" -> V.scala213,
      "scala3" -> V.scala3,
    ),
  )
  .dependsOn(mtags)
  .enablePlugins(BuildInfoPlugin)

lazy val `sbt-metals` = project
  .settings(
    buildInfoPackage := "scala.meta.internal.sbtmetals",
    buildInfoKeys := Seq[BuildInfoKey](
      "semanticdbVersion" -> V.semanticdb,
      "supportedScala2Versions" -> V.scala2Versions,
      "javaSemanticdbVersion" -> V.javaSemanticdb,
    ),
    scalaVersion := V.scala212,
    scriptedLaunchOpts ++= Seq(s"-Dplugin.version=${version.value}"),
  )
  .enablePlugins(BuildInfoPlugin, SbtPlugin)
  .disablePlugins(ScalafixPlugin)

lazy val input = project
  .in(file("tests/input"))
  .settings(
    sharedSettings,
    publish / skip := true,
    libraryDependencies ++= List(
      // these projects have macro annotations
      "org.scalameta" %% "scalameta" % V.scalameta,
      "io.circe" %% "circe-derivation-annotations" % "0.13.0-M5",
    ),
    scalacOptions ++= Seq("-P:semanticdb:synthetics:on", "-Ymacro-annotations"),
    scalacOptions ~= { options =>
      options.filter(_ != "-Wunused")
    },
  )
  .disablePlugins(ScalafixPlugin)

lazy val input3 = project
  .in(file("tests/input3"))
  .settings(
    sharedSettings,
    scalaVersion := V.scala3,
    publish / skip := true,
  )
  .disablePlugins(ScalafixPlugin)

lazy val testSettings: Seq[Def.Setting[_]] = List(
  Test / parallelExecution := false,
  publish / skip := true,
  fork := true,
  testFrameworks := List(TestFrameworks.MUnit),
  Test / testOptions ++= {
    if (isCI) {
      // Enable verbose logging using sbt loggers in CI.
      List(Tests.Argument(TestFrameworks.MUnit, "+l", "--verbose", "-F"))
    } else {
      Nil
    }
  },
)

def runMtagsPublishLocal(
    state: State,
    scalaV: String,
    projectV: String,
): State = {
  val newState = Project
    .extract(state)
    .appendWithSession(
      List(
        mtags / scalaVersion := scalaV,
        ThisBuild / version := projectV,
        ThisBuild / useSuperShell := false,
      ),
      state,
    )
  val (s, _) = Project
    .extract(newState)
    .runTask(mtags / publishLocal, newState)
  s
}

def crossPublishLocal(scalaV: String) =
  Def.task[Unit] {
    val versionValue = (ThisBuild / version).value
    // Runs `publishLocal` for mtags with `scalaVersion := $scalaV`
    runMtagsPublishLocal(state.value, scalaV, versionValue)
  }

def publishAllMtags(
    all: List[String]
): sbt.Def.Initialize[sbt.Task[Unit]] = {
  all match {
    case Nil =>
      throw new Exception("The Scala versions list cannot be empty")
    case head :: Nil =>
      crossPublishLocal(head)
    case head :: tl =>
      crossPublishLocal(head).dependsOn(publishAllMtags(tl))
  }
}

def publishBinaryMtags =
  (interfaces / publishLocal)
    .dependsOn(
      publishAllMtags(V.quickPublishScalaVersions)
    )

lazy val mtest = project
  .in(file("tests/mtest"))
  .settings(
    testSettings,
    sharedSettings,
    libraryDependencies ++= List(
      "org.scalameta" %% "munit" % V.munit,
      "io.get-coursier" % "interface" % V.coursierInterfaces,
    ),
    buildInfoPackage := "tests",
    buildInfoObject := "BuildInfoVersions",
    buildInfoKeys := Seq[BuildInfoKey](
      "scala211" -> V.scala211,
      "scala212" -> V.scala212,
      "scala213" -> V.scala213,
      "scala3" -> V.scala3,
      "scala2Versions" -> V.scala2Versions,
      "scala3Versions" -> (V.scala3Versions ++ V.nightlyScala3Versions),
      "scala2Versions" -> V.scala2Versions,
      "scalaVersion" -> scalaVersion.value,
      "kindProjector" -> V.kindProjector,
      "betterMonadicFor" -> V.betterMonadicFor,
    ),
    crossScalaVersions := V.nonDeprecatedScalaVersions,
    Compile / unmanagedSourceDirectories ++= multiScalaDirectories(
      (ThisBuild / baseDirectory).value / "tests" / "mtest",
      scalaVersion.value,
    ),
  )
  .dependsOn(mtags)
  .enablePlugins(BuildInfoPlugin)

lazy val cross = project
  .in(file("tests/cross"))
  .settings(
    testSettings,
    sharedSettings,
    crossScalaVersions := V.nonDeprecatedScalaVersions,
  )
  .dependsOn(mtest, mtags)

def isInTestShard(name: String, logger: Logger): Boolean = {
  val groupIndex = TestGroups.testGroups.indexWhere(group => group(name))
  if (groupIndex == -1) {
    logger.warn(
      s"""|Test is not contained in a shard: $name
          |It will be executed by default in the first shard.
          |Please add it to "project/TestGroups.scala". """.stripMargin
    )
  }
  if (!isCI) {
    true
  } else {
    val groupId = Math.max(0, groupIndex) + 1
    System.getenv("TEST_SHARD").toInt == groupId
  }
}

lazy val metalsDependencies = project
  .in(file("target/.dependencies"))
  .settings(
    publish / skip := true,
    libraryDependencies ++= List(
      // The dependencies listed below are only listed so Scala Steward
      // will pick them up and update them. They aren't actually used.
      "com.lihaoyi" %% "ammonite-util" % V.ammonite,
      "org.typelevel" % "kind-projector" % V.kindProjector cross CrossVersion.full,
      "com.olegpy" %% "better-monadic-for" % V.betterMonadicFor,
      "com.lihaoyi" % "mill-contrib-testng" % V.mill,
      "org.virtuslab.scala-cli" % "cli_3" % V.scalaCli intransitive (),
    ),
  )
  .disablePlugins(ScalafixPlugin)

lazy val unit = project
  .in(file("tests/unit"))
  .settings(
    testSettings,
    Test / testOptions ++= Seq(
      Tests.Filter(name => isInTestShard(name, sLog.value))
    ),
    sharedSettings,
    Test / javaOptions += "-Xmx2G",
    libraryDependencies ++= List(
      "io.get-coursier" %% "coursier" % V.coursier, // for jars
      "ch.epfl.scala" %% "bloop-config" % V.bloop,
      "org.scalameta" %% "munit" % V.munit,
    ),
    buildInfoPackage := "tests",
    Compile / resourceGenerators += InputProperties
      .resourceGenerator(input, input3),
    Compile / compile :=
      (Compile / compile)
        .dependsOn(
          input / Test / compile,
          input3 / Test / compile,
        )
        .value,
    buildInfoKeys := Seq[BuildInfoKey](
      "sourceroot" -> (ThisBuild / baseDirectory).value,
      "targetDirectory" -> (Test / target).value,
      "testResourceDirectory" -> (Test / resourceDirectory).value,
      "scalaVersion" -> scalaVersion.value,
    ),
  )
  .dependsOn(mtest, metals)
  .enablePlugins(BuildInfoPlugin)

lazy val slow = project
  .in(file("tests/slow"))
  .settings(
    testSettings,
    sharedSettings,
    Test / testOnly := (Test / testOnly)
      .dependsOn((`sbt-metals` / publishLocal), publishBinaryMtags)
      .evaluated,
    Test / test := (Test / test)
      .dependsOn(`sbt-metals` / publishLocal, publishBinaryMtags)
      .value,
  )
  .dependsOn(unit)

lazy val bench = project
  .in(file("metals-bench"))
  .enablePlugins(BuildInfoPlugin)
  .settings(
    sharedSettings,
    run / fork := true,
    publish / skip := true,
    moduleName := "metals-bench",
    buildInfoKeys := Seq[BuildInfoKey](scalaVersion),
    buildInfoPackage := "bench",
    Jmh / bspEnabled := false,
  )
  .dependsOn(unit)
  .enablePlugins(JmhPlugin)

lazy val docs = project
  .in(file("metals-docs"))
  .settings(
    sharedSettings,
    publish / skip := true,
    moduleName := "metals-docs",
    mdoc := (Compile / run).evaluated,
    dependencyOverrides += "com.lihaoyi" %% "pprint" % "0.6.6",
  )
  .dependsOn(metals)
  .enablePlugins(DocusaurusPlugin)
