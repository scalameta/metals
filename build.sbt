import scala.collection.mutable
import scala.sys.process._
import Tests._

def localSnapshotVersion = "0.10.10-SNAPSHOT"
def isCI = System.getenv("CI") != null

def isScala211(v: Option[(Long, Long)]): Boolean = v.contains((2, 11))
def isScala212(v: Option[(Long, Long)]): Boolean = v.contains((2, 12))
def isScala213(v: Option[(Long, Long)]): Boolean = v.contains((2, 13))
def isScala2(v: Option[(Long, Long)]): Boolean = v.exists(_._1 == 2)
def isScala3(v: Option[(Long, Long)]): Boolean = v.exists(_._1 == 3)

def crossSetting[A](
    scalaVersion: String,
    if211: List[A] = Nil,
    if2: List[A] = Nil,
    ifLaterThan211: List[A] = Nil,
    if3: List[A] = Nil
): List[A] =
  CrossVersion.partialVersion(scalaVersion) match {
    case partialVersion if isScala211(partialVersion) => if211 ::: if2
    case partialVersion if isScala212(partialVersion) => ifLaterThan211 ::: if2
    case partialVersion if isScala213(partialVersion) => ifLaterThan211 ::: if2
    case partialVersion if isScala3(partialVersion) => if3
    case _ => Nil
  }

// -Xlint is unusable because of
// https://github.com/scala/bug/issues/10448
val scala212CompilerOptions = List(
  "-Ywarn-unused:imports",
  "-Ywarn-unused:privates",
  "-Ywarn-unused:locals",
  "-Ywarn-unused:patvars",
  "-Ywarn-unused:implicits"
)

logo := Welcome.logo
usefulTasks := Welcome.tasks

inThisBuild(
  List(
    version ~= { dynVer =>
      if (isCI) dynVer
      else localSnapshotVersion // only for local publishing
    },
    // note bucket created by @tgodzik
    scalaVersion := V.scala212,
    crossScalaVersions := List(V.scala212),
    scalacOptions ++= List(
      "-target:jvm-1.8",
      "-Yrangepos"
    ) ::: scala212CompilerOptions,
    scalafixDependencies += "com.github.liancheng" %% "organize-imports" % V.organizeImportRule,
    organization := "org.scalameta",
    licenses := Seq(
      "Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")
    ),
    homepage := Some(url("https://github.com/scalameta/metals")),
    developers := List(
      Developer(
        "adpi2",
        "Adrien Piquerez",
        "adrien.piquerez@epfl.ch",
        url("https://github.com/adpi2")
      ),
      Developer(
        "laughedelic",
        "Alexey Alekhin",
        "laughedelic@gmail.com",
        url("https://github.com/laughedelic")
      ),
      Developer(
        "ckipp01",
        "Chris Kipp",
        "ckipp@pm.me",
        url("https://chris-kipp.io")
      ),
      Developer(
        "gabro",
        "Gabriele Petronella",
        "gabriele@buildo.io",
        url("https://github.com/gabro")
      ),
      Developer(
        "mudsam",
        "Johan Mudsam",
        "johan@mudsam.com",
        url("https://github.com/mudsam")
      ),
      Developer(
        "jvican",
        "Jorge Vicente Cantero",
        "jorgevc@fastmail.es",
        url("https://jvican.github.io/")
      ),
      Developer(
        "kpbochenek",
        "Krzysztof Bochenek",
        "kbochenek@virtuslab.com ",
        url("https://github.com/kpbochenek")
      ),
      Developer(
        "marek1840",
        "Marek Żarnowski",
        "mzarnowski@virtuslab.com",
        url("https://github.com/marek1840")
      ),
      Developer(
        "olafurpg",
        "Ólafur Páll Geirsson",
        "olafurpg@gmail.com",
        url("https://geirsson.com")
      ),
      Developer(
        "ShaneDelmore",
        "Shane Delmore",
        "sdelmore@twitter.com",
        url("http://delmore.io")
      ),
      Developer(
        "tgodzik",
        "Tomasz Godzik",
        "tgodzik@virtuslab.com",
        url("https://github.com/tgodzik")
      ),
      Developer(
        "dos65",
        "Vadim Chelyshov",
        "vchelyshov@virtuslab.com",
        url("https://github.com/dos65")
      )
    ),
    testFrameworks := List(),
    resolvers += Resolver.sonatypeRepo("public"),
    resolvers += Resolver.sonatypeRepo("snapshot"),
    dependencyOverrides += V.guava,
    // faster publishLocal:
    packageDoc / publishArtifact := sys.env.contains("CI"),
    packageSrc / publishArtifact := sys.env.contains("CI"),
    // forking options
    javaOptions += {
      import scala.collection.JavaConverters._
      val props = System.getProperties
      props
        .stringPropertyNames()
        .asScala
        .map { configKey => s"-D$configKey=${props.getProperty(configKey)}" }
        .mkString(" ")
    },
    resolvers += Resolver.bintrayRepo("scalacenter", "releases")
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
        |""".stripMargin.getBytes()
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
  "+publishLocal; metals/runMain scala.meta.metals.DownloadDependencies "
)

commands += Command.command("save-expect") { s =>
  "unit/test:runMain tests.SaveExpect" ::
    s
}

lazy val V = new {
  val scala210 = "2.10.7"
  val scala211 = "2.11.12"
  val sbtScala = "2.12.14"
  val scala212 = "2.12.15"
  val scala213 = "2.13.7"
  val ammonite212Version = "2.12.13"
  val ammonite213Version = "2.13.6"
  val scalameta = "4.4.30"
  val semanticdb = scalameta
  val bsp = "2.0.0-M15"
  val bloop = "1.4.11"
  val scala3 = "3.1.0"
  val nextScala3RC = "3.1.1-RC1"
  val bloopNightly = bloop
  val sbtBloop = bloop
  val gradleBloop = bloop
  val mavenBloop = bloop
  val mdoc = "2.2.24"
  val scalafmt = "3.0.5"
  val munit = "0.7.29"
  val scalafix = "0.9.31"
  val lsp4jV = "0.12.0"
  val sbtJdiTools = "1.1.1"
  val genyVersion = "0.6.10"
  val debugAdapter = "2.0.8"

  // List of supported Scala versions in SemanticDB. Needs to be manually updated
  // for every SemanticDB upgrade.
  def supportedScalaBinaryVersions =
    supportedScalaVersions.iterator
      .map(CrossVersion.partialVersion)
      .collect {
        case Some((3, _)) => "3"
        case Some((a, b)) => s"$a.$b"
      }
      .toList
      .distinct

  // Scala 2
  def deprecatedScala2Versions =
    Seq(
      scala211,
      "2.12.8",
      "2.12.9",
      "2.12.10",
      "2.13.0",
      "2.13.1",
      "2.13.2",
      "2.13.3"
    )
  def nonDeprecatedScala2Versions =
    Seq(
      scala213,
      scala212,
      "2.12.14",
      "2.12.13",
      "2.12.12",
      "2.12.11",
      "2.13.4",
      "2.13.5",
      "2.13.6"
    )
  def scala2Versions = nonDeprecatedScala2Versions ++ deprecatedScala2Versions

  // Scala 3
  def nonDeprecatedScala3Versions = Seq(nextScala3RC, scala3, "3.0.2")
  def deprecatedScala3Versions = Seq("3.0.1", "3.0.0")
  def scala3Versions = nonDeprecatedScala3Versions ++ deprecatedScala3Versions

  def supportedScalaVersions = scala2Versions ++ scala3Versions
  def nonDeprecatedScalaVersions =
    nonDeprecatedScala2Versions ++ nonDeprecatedScala3Versions
  def deprecatedScalaVersions =
    deprecatedScala2Versions ++ deprecatedScala3Versions

  def guava = "com.google.guava" % "guava" % "31.0.1-jre"
  def lsp4j = "org.eclipse.lsp4j" % "org.eclipse.lsp4j" % lsp4jV
  def dap4j = "org.eclipse.lsp4j" % "org.eclipse.lsp4j.debug" % lsp4jV
  val coursierInterfaces = "1.0.4"
  val coursier = "2.0.16"
  val ammonite = "2.3.8-124-2da846d2"
  val mill = "0.9.9"
  val organizeImportRule = "0.6.0"
}

val sharedSettings = List(
  libraryDependencies ++= crossSetting(
    scalaVersion.value,
    if2 = List(
      compilerPlugin(
        "org.scalameta" % "semanticdb-scalac" % V.scalameta cross CrossVersion.full
      )
    )
  ),
  scalacOptions ++= crossSetting(
    scalaVersion.value,
    if3 = List(
      "-language:implicitConversions",
      "-Xtarget:8",
      "-Xsemanticdb"
    ),
    if211 = List("-Xexperimental", "-Ywarn-unused-import")
  ),
  scalacOptions --= crossSetting(
    scalaVersion.value,
    if3 = "-Yrangepos" :: "-target:jvm-1.8" :: scala212CompilerOptions,
    if211 = scala212CompilerOptions
  )
)

publish / skip := true

lazy val interfaces = project
  .in(file("mtags-interfaces"))
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
      "implNote:a:Implementation Note:"
    )
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
  crossScalaVersions := V.supportedScalaVersions,
  crossTarget := target.value / s"scala-${scalaVersion.value}",
  crossVersion := CrossVersion.full,
  Compile / unmanagedSourceDirectories ++= multiScalaDirectories(
    (ThisBuild / baseDirectory).value / "mtags",
    scalaVersion.value
  ),
  // @note needed to deal with issues with dottyDoc
  Compile / doc / sources := Seq.empty,
  libraryDependencies +=
    "com.thoughtworks.qdox" % "qdox" % "2.0.0", // for java mtags
  libraryDependencies ++= crossSetting(
    scalaVersion.value,
    if2 = List(
      // for token edit-distance used by goto definition
      "com.googlecode.java-diff-utils" % "diffutils" % "1.3.0",
      "org.jsoup" % "jsoup" % "1.14.3", // for extracting HTML from javadocs
      "com.lihaoyi" %% "geny" % V.genyVersion,
      "org.scala-lang.modules" %% "scala-java8-compat" % "1.0.2",
      "org.scalameta" % "semanticdb-scalac-core" % V.scalameta cross CrossVersion.full
    ),
    if3 = List(
      "com.fasterxml.jackson.core" % "jackson-databind" % "2.13.0",
      ("org.scala-lang.modules" %% "scala-java8-compat" % "1.0.2")
        .cross(CrossVersion.for3Use2_13),
      ("com.lihaoyi" %% "geny" % V.genyVersion)
        .cross(CrossVersion.for3Use2_13),
      "org.scala-lang" %% "scala3-compiler" % scalaVersion.value,
      ("org.scalameta" %% "scalameta" % V.scalameta)
        .cross(CrossVersion.for3Use2_13)
        .exclude("org.scala-lang", "scala-reflect")
        .exclude("org.scala-lang", "scala-compiler")
    )
  ),
  libraryDependencies ++= List("org.lz4" % "lz4-java" % "1.8.0"),
  libraryDependencies ++= {
    if (isCI) Nil
    // NOTE(olafur) pprint is indispensable for me while developing, I can't
    // use println anymore for debugging because pprint.log is 100 times better.
    else {
      List("com.lihaoyi" %% "pprint" % "0.6.6")
    }
  },
  buildInfoPackage := "scala.meta.internal.mtags",
  buildInfoKeys := Seq[BuildInfoKey](
    "scalaCompilerVersion" -> scalaVersion.value
  )
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
    )
  )
  .dependsOn(interfaces)
  .enablePlugins(BuildInfoPlugin)

lazy val mtags = project
  .settings(
    sharedSettings,
    mtagsSettings,
    moduleName := "mtags"
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
      "com.geirsson" %% "metaconfig-core" % "0.9.15",
      // for measuring memory footprint
      "org.openjdk.jol" % "jol-core" % "0.16",
      // for file watching
      "com.swoval" % "file-tree-views" % "2.1.7",
      // for http client
      "io.undertow" % "undertow-core" % "2.2.12.Final",
      "org.jboss.xnio" % "xnio-nio" % "3.8.4.Final",
      // for persistent data like "dismissed notification"
      "org.flywaydb" % "flyway-core" % "8.0.2",
      "com.h2database" % "h2" % "1.4.200",
      // for BSP
      "org.scala-sbt.ipcsocket" % "ipcsocket" % "1.4.0",
      "ch.epfl.scala" % "bsp4j" % V.bsp,
      "ch.epfl.scala" %% "bloop-launcher" % V.bloopNightly,
      // for LSP
      V.lsp4j,
      // for DAP
      V.dap4j,
      // for producing SemanticDB from Java source files
      "com.thoughtworks.qdox" % "qdox" % "2.0.0",
      // for finding paths of global log/cache directories
      "dev.dirs" % "directories" % "26",
      // ==================
      // Scala dependencies
      // ==================
      "org.scala-lang.modules" %% "scala-java8-compat" % "1.0.2",
      "org.scalameta" % "mdoc-interfaces" % V.mdoc,
      "org.scalameta" %% "scalafmt-dynamic" % V.scalafmt,
      "ch.epfl.scala" % "scalafix-interfaces" % V.scalafix,
      // For reading classpaths.
      // for fetching ch.epfl.scala:bloop-frontend and other library dependencies
      "io.get-coursier" % "interface" % V.coursierInterfaces,
      // for logging
      "com.outr" %% "scribe" % "3.6.3",
      "com.outr" %% "scribe-file" % "3.6.3",
      "com.outr" %% "scribe-slf4j" % "3.6.3", // needed for flyway database migrations
      // for debugging purposes, not strictly needed but nice for productivity
      "com.lihaoyi" %% "pprint" % "0.6.2",
      // for JSON formatted doctor
      "com.lihaoyi" %% "ujson" % "1.4.2",
      // For remote language server
      "com.lihaoyi" %% "requests" % "0.6.9",
      // for producing SemanticDB from Scala source files
      "org.scalameta" %% "scalameta" % V.scalameta,
      "org.scalameta" % "semanticdb-scalac-core" % V.scalameta cross CrossVersion.full,
      // For starting Ammonite
      "io.github.alexarchambault.ammonite" %% "ammonite-runner" % "0.3.2"
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
      "scalafmtVersion" -> V.scalafmt,
      "ammoniteVersion" -> V.ammonite,
      "organizeImportVersion" -> V.organizeImportRule,
      "millVersion" -> V.mill,
      "debugAdapterVersion" -> V.debugAdapter,
      "sbtJdiToolsVersion" -> V.sbtJdiTools,
      "supportedScalaVersions" -> V.supportedScalaVersions,
      "supportedScala2Versions" -> V.scala2Versions,
      "supportedScala3Versions" -> V.scala3Versions,
      "supportedScalaBinaryVersions" -> V.supportedScalaBinaryVersions,
      "deprecatedScalaVersions" -> V.deprecatedScalaVersions,
      "nonDeprecatedScalaVersions" -> V.nonDeprecatedScalaVersions,
      "scala211" -> V.scala211,
      "scala212" -> V.scala212,
      "ammonite212" -> V.ammonite212Version,
      "ammonite213" -> V.ammonite213Version,
      "scala213" -> V.scala213,
      "scala3" -> V.scala3,
      "nextScala3RC" -> V.nextScala3RC
    )
  )
  .dependsOn(mtags)
  .enablePlugins(BuildInfoPlugin)

lazy val `sbt-metals` = project
  .settings(
    buildInfoPackage := "scala.meta.internal.sbtmetals",
    buildInfoKeys := Seq[BuildInfoKey](
      "semanticdbVersion" -> V.semanticdb,
      "supportedScala2Versions" -> V.scala2Versions
    ),
    scriptedLaunchOpts ++= Seq(s"-Dplugin.version=${version.value}")
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
      "io.circe" %% "circe-derivation-annotations" % "0.9.0-M5"
    ),
    scalacOptions += "-P:semanticdb:synthetics:on",
    addCompilerPlugin(
      "org.scalamacros" % "paradise" % "2.1.1" cross CrossVersion.full
    )
  )
  .disablePlugins(ScalafixPlugin)

lazy val input3 = project
  .in(file("tests/input3"))
  .settings(
    sharedSettings,
    scalaVersion := V.scala3,
    publish / skip := true
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
  }
)

def crossPublishLocal(scalaV: String) =
  Def.task[Unit] {
    val versionValue = (ThisBuild / version).value
    // Runs `publishLocal` for mtags with `scalaVersion := $scalaV`
    val newState = Project
      .extract(state.value)
      .appendWithSession(
        List(
          mtags / scalaVersion := scalaV,
          ThisBuild / version := versionValue,
          ThisBuild / useSuperShell := false
        ),
        state.value
      )
    val (s, _) = Project
      .extract(newState)
      .runTask(mtags / publishLocal, newState)
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
      publishAllMtags(
        Set(
          V.scala211,
          V.sbtScala,
          V.scala212,
          V.ammonite212Version,
          V.scala213,
          V.ammonite213Version,
          V.scala3
        ).toList
      )
    )

lazy val mtest = project
  .in(file("tests/mtest"))
  .settings(
    testSettings,
    sharedSettings,
    libraryDependencies ++= List(
      "org.scalameta" %% "munit" % V.munit,
      "io.get-coursier" % "interface" % V.coursierInterfaces
    ),
    buildInfoPackage := "tests",
    buildInfoObject := "BuildInfoVersions",
    buildInfoKeys := Seq[BuildInfoKey](
      "scala211" -> V.scala211,
      "scala212" -> V.scala212,
      "scala213" -> V.scala213,
      "scala3" -> V.scala3,
      "scala2Versions" -> V.scala2Versions,
      "scala3Versions" -> V.scala3Versions,
      "scala2Versions" -> V.scala2Versions,
      "scalaVersion" -> scalaVersion.value
    ),
    crossScalaVersions := V.nonDeprecatedScalaVersions,
    Compile / unmanagedSourceDirectories ++= multiScalaDirectories(
      (ThisBuild / baseDirectory).value / "tests" / "mtest",
      scalaVersion.value
    )
  )
  .dependsOn(mtags)
  .enablePlugins(BuildInfoPlugin)

lazy val cross = project
  .in(file("tests/cross"))
  .settings(
    testSettings,
    sharedSettings,
    crossScalaVersions := V.nonDeprecatedScalaVersions
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
      // The dependencies listed below are only listed so Scala Steward
      // will pick them up and update them. They aren't actually used.
      "com.lihaoyi" %% "ammonite-util" % V.ammonite intransitive (),
      "com.lihaoyi" % "mill-contrib-testng" % V.mill intransitive ()
    ),
    buildInfoPackage := "tests",
    Compile / resourceGenerators += InputProperties
      .resourceGenerator(input, input3),
    Compile / compile :=
      (Compile / compile)
        .dependsOn(
          input / Test / compile,
          input3 / Test / compile
        )
        .value,
    buildInfoKeys := Seq[BuildInfoKey](
      "sourceroot" -> (ThisBuild / baseDirectory).value,
      "targetDirectory" -> (Test / target).value,
      "testResourceDirectory" -> (Test / resourceDirectory).value,
      "scalaVersion" -> scalaVersion.value
    )
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
      .value
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
    libraryDependencies ++= List(
      // for measuring memory usage
      "org.spire-math" %% "clouseau" % "0.2.2"
    ),
    buildInfoKeys := Seq[BuildInfoKey](scalaVersion),
    buildInfoPackage := "bench",
    Jmh / bspEnabled := false
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
    libraryDependencies ++= List(
      "org.jsoup" % "jsoup" % "1.14.3"
    )
  )
  .dependsOn(metals)
  .enablePlugins(DocusaurusPlugin)
