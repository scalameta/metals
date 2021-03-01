import scala.collection.mutable
import scala.sys.process._
import Tests._

def localSnapshotVersion = "0.10.1-SNAPSHOT"
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

val MUnitFramework = new TestFramework("munit.Framework")

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
    munitBucketName := Some("scala-metals-test-reports"),
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
      )
    ),
    testFrameworks := List(),
    resolvers += Resolver.sonatypeRepo("public"),
    dependencyOverrides += V.guava,
    // faster publishLocal:
    publishArtifact.in(packageDoc) := sys.env.contains("CI"),
    publishArtifact.in(packageSrc) := sys.env.contains("CI"),
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

onLoad.in(Global) ~= { old =>
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
cancelable.in(Global) := true
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
  val sbtScala = "2.12.12"
  val scala212 = "2.12.13"
  val scala213 = "2.13.5"
  val ammonite212Version = scala212
  // TODO https://github.com/scalameta/metals/issues/2392
  val ammonite213Version = "2.13.4"
  val scalameta = "4.4.10"
  val semanticdb = scalameta
  val bsp = "2.0.0-M13"
  val bloop = "1.4.8"
  val scala3 = "3.0.0-RC1"
  val bloopNightly = bloop
  val sbtBloop = bloop
  val gradleBloop = bloop
  val mavenBloop = bloop
  val mdoc = "2.2.18"
  val scalafmt = "2.7.4"
  val munit = "0.7.22"
  val scalafix = "0.9.25"
  val lsp4jV = "0.10.0"
  val sbtJdiTools = "1.1.1"

  // List of supported Scala versions in SemanticDB. Needs to be manually updated
  // for every SemanticDB upgrade.
  def supportedScalaBinaryVersions =
    supportedScalaVersions.iterator
      .map(CrossVersion.partialVersion)
      .collect { case Some((a, b)) => s"$a.$b" }
      .toList
      .distinct

  // Scala 2
  def deprecatedScala2Versions =
    Seq(scala211, "2.12.8", "2.12.9", "2.13.0", "2.13.1", "2.13.2")
  def nonDeprecatedScala2Versions =
    Seq(scala213, scala212, "2.12.12", "2.12.11", "2.12.13", "2.13.3", "2.13.4")
  def scala2Versions = nonDeprecatedScala2Versions ++ deprecatedScala2Versions

  // Scala 3
  def nonDeprecatedScala3Versions = Seq(scala3, "3.0.0-M3")
  def deprecatedScala3Versions = Seq("3.0.0-M2", "3.0.0-M1")
  def scala3Versions = nonDeprecatedScala3Versions ++ deprecatedScala3Versions

  def supportedScalaVersions = scala2Versions ++ scala3Versions
  def nonDeprecatedScalaVersions =
    nonDeprecatedScala2Versions ++ nonDeprecatedScala3Versions
  def deprecatedScalaVersions =
    deprecatedScala2Versions ++ deprecatedScala3Versions

  def guava = "com.google.guava" % "guava" % "30.1-jre"
  def lsp4j = "org.eclipse.lsp4j" % "org.eclipse.lsp4j" % lsp4jV
  def dap4j = "org.eclipse.lsp4j" % "org.eclipse.lsp4j.debug" % lsp4jV
  val coursierInterfaces = "1.0.2"
  val coursier = "2.0.11"
  val ammonite = "2.3.8-36-1cce53f3"
  val mill = "0.9.3"
  val organizeImportRule = "0.4.4"
}

val genyVersion = Def.setting {
  if (scalaVersion.value.startsWith("2.11")) "0.1.6"
  else "0.4.2"
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
    if3 = List("-language:implicitConversions"),
    if211 = List("-Xexperimental", "-Ywarn-unused-import")
  ),
  scalacOptions --= crossSetting(
    scalaVersion.value,
    if3 = "-Yrangepos" :: scala212CompilerOptions,
    if211 = scala212CompilerOptions
  )
)

skip.in(publish) := true

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
    javacOptions in (Compile / doc) ++= List(
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
  unmanagedSourceDirectories.in(Compile) ++= multiScalaDirectories(
    baseDirectory.in(ThisBuild).value / "mtags",
    scalaVersion.value
  ),
  // @note needed to deal with issues with dottyDoc
  sources in (Compile, doc) := Seq.empty,
  libraryDependencies ++= crossSetting(
    scalaVersion.value,
    if2 = List(
      // for token edit-distance used by goto definition
      "com.googlecode.java-diff-utils" % "diffutils" % "1.3.0",
      "com.thoughtworks.qdox" % "qdox" % "2.0.0", // for java mtags
      "org.jsoup" % "jsoup" % "1.13.1", // for extracting HTML from javadocs
      "com.lihaoyi" %% "geny" % genyVersion.value,
      "org.scala-lang.modules" %% "scala-java8-compat" % "0.9.1",
      "org.scalameta" % "semanticdb-scalac-core" % V.scalameta cross CrossVersion.full
    ),
    if3 = List(
      "com.fasterxml.jackson.core" % "jackson-databind" % "2.12.1",
      ("org.scala-lang.modules" %% "scala-java8-compat" % "0.9.1")
        .withDottyCompat(scalaVersion.value),
      ("com.lihaoyi" %% "geny" % genyVersion.value)
        .withDottyCompat(scalaVersion.value),
      "org.scala-lang" %% "scala3-compiler" % scalaVersion.value,
      ("org.scalameta" %% "scalameta" % V.scalameta)
        .withDottyCompat(scalaVersion.value)
    )
  ),
  libraryDependencies ++= List("org.lz4" % "lz4-java" % "1.7.1"),
  libraryDependencies ++= {
    if (isCI) Nil
    // NOTE(olafur) pprint is indispensable for me while developing, I can't
    // use println anymore for debugging because pprint.log is 100 times better.
    else
      crossSetting(
        scalaVersion.value,
        if211 = List("com.lihaoyi" %% "pprint" % "0.5.4"),
        ifLaterThan211 = List("com.lihaoyi" %% "pprint" % "0.6.1"),
        if3 = List(
          ("com.lihaoyi" %% "pprint" % "0.6.1")
            .withDottyCompat(scalaVersion.value)
        )
      )
  },
  buildInfoPackage := "scala.meta.internal.mtags",
  buildInfoKeys := Seq[BuildInfoKey](
    "scalaCompilerVersion" -> scalaVersion.value
  )
)

lazy val mtags3 = project
  .in(file(".mtags"))
  .settings(
    unmanagedSourceDirectories.in(Compile) := Seq(),
    sharedSettings,
    mtagsSettings,
    unmanagedSourceDirectories.in(Compile) += baseDirectory
      .in(ThisBuild)
      .value / "mtags" / "src" / "main" / "scala",
    moduleName := "mtags3",
    scalaVersion := V.scala3,
    target := baseDirectory
      .in(ThisBuild)
      .value / "mtags" / "target" / "target3",
    skip.in(publish) := true
  )
  .dependsOn(interfaces)
  .disablePlugins(ScalafixPlugin)
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
    fork.in(Compile, run) := true,
    mainClass.in(Compile) := Some("scala.meta.metals.Main"),
    // As a general rule of thumb, we try to keep Scala dependencies to a minimum.
    libraryDependencies ++= List(
      // =================
      // Java dependencies
      // =================
      // for bloom filters
      V.guava,
      "com.geirsson" %% "metaconfig-core" % "0.9.10",
      // for measuring memory footprint
      "org.openjdk.jol" % "jol-core" % "0.14",
      // for file watching
      "com.swoval" % "file-tree-views" % "2.1.6",
      // for http client
      "io.undertow" % "undertow-core" % "2.2.4.Final",
      "org.jboss.xnio" % "xnio-nio" % "3.8.4.Final",
      // for persistent data like "dismissed notification"
      "org.flywaydb" % "flyway-core" % "7.5.3",
      "com.h2database" % "h2" % "1.4.200",
      // for starting embedded buildTool processes
      "com.zaxxer" % "nuprocess" % "2.0.1",
      "net.java.dev.jna" % "jna" % "5.7.0",
      "net.java.dev.jna" % "jna-platform" % "5.7.0",
      // for BSP
      "org.scala-sbt.ipcsocket" % "ipcsocket" % "1.3.0",
      "ch.epfl.scala" % "bsp4j" % V.bsp,
      "ch.epfl.scala" %% "bloop-launcher" % V.bloopNightly,
      // for LSP
      V.lsp4j,
      // for DAP
      V.dap4j,
      // for producing SemanticDB from Java source files
      "com.thoughtworks.qdox" % "qdox" % "2.0.0",
      // for finding paths of global log/cache directories
      "dev.dirs" % "directories" % "23",
      // ==================
      // Scala dependencies
      // ==================
      "org.scala-lang.modules" %% "scala-java8-compat" % "0.9.1",
      "org.scalameta" % "mdoc-interfaces" % V.mdoc,
      "org.scalameta" %% "scalafmt-dynamic" % V.scalafmt,
      "ch.epfl.scala" % "scalafix-interfaces" % V.scalafix,
      // For reading classpaths.
      // for fetching ch.epfl.scala:bloop-frontend and other library dependencies
      "io.get-coursier" % "interface" % V.coursierInterfaces,
      // for logging
      "com.outr" %% "scribe" % "3.3.3",
      "com.outr" %% "scribe-file" % "3.3.3",
      "com.outr" %% "scribe-slf4j" % "3.3.3", // needed for flyway database migrations
      // for debugging purposes, not strictly needed but nice for productivity
      "com.lihaoyi" %% "pprint" % "0.6.1",
      // for JSON formatted doctor
      "com.lihaoyi" %% "ujson" % "1.2.3",
      // For remote language server
      "com.lihaoyi" %% "requests" % "0.6.5",
      // for producing SemanticDB from Scala source files
      "org.scalameta" %% "scalameta" % V.scalameta,
      "org.scalameta" % "semanticdb-scalac-core" % V.scalameta cross CrossVersion.full,
      // For starting Ammonite
      "io.github.alexarchambault.ammonite" %% "ammonite-runner" % "0.3.1"
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
      "scala3" -> V.scala3
    )
  )
  .dependsOn(mtags)
  .enablePlugins(BuildInfoPlugin)

lazy val `sbt-metals` = project
  .settings(
    sbtPlugin := true,
    buildInfoPackage := "scala.meta.internal.sbtmetals",
    buildInfoKeys := Seq[BuildInfoKey](
      "semanticdbVersion" -> V.semanticdb,
      "supportedScala2Versions" -> V.scala2Versions
    ),
    addSbtPlugin("ch.epfl.scala" % "sbt-debug-adapter" % "1.0.0")
  )
  .enablePlugins(BuildInfoPlugin)
  .disablePlugins(ScalafixPlugin)

lazy val input = project
  .in(file("tests/input"))
  .settings(
    sharedSettings,
    skip.in(publish) := true,
    scalacOptions ++= List(
      "-P:semanticdb:synthetics:on"
    ),
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
    skip.in(publish) := true
  )
  .disablePlugins(ScalafixPlugin)

lazy val testSettings: Seq[Def.Setting[_]] = List(
  Test / parallelExecution := false,
  skip.in(publish) := true,
  fork := true,
  testFrameworks := List(MUnitFramework),
  testOptions.in(Test) ++= {
    if (isCI) {
      // Enable verbose logging using sbt loggers in CI.
      List(Tests.Argument(MUnitFramework, "+l", "--verbose"))
    } else {
      Nil
    }
  }
)

def crossPublishLocal(scalaV: String) =
  Def.task[Unit] {
    val versionValue = version.in(ThisBuild).value
    // Runs `publishLocal` for mtags with `scalaVersion := $scalaV`
    val newState = Project
      .extract(state.value)
      .appendWithSession(
        List(
          scalaVersion.in(mtags) := scalaV,
          version.in(ThisBuild) := versionValue,
          useSuperShell.in(ThisBuild) := false
        ),
        state.value
      )
    val (s, _) = Project
      .extract(newState)
      .runTask(publishLocal.in(mtags), newState)
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
  publishLocal
    .in(interfaces)
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
    unmanagedSourceDirectories.in(Compile) ++= multiScalaDirectories(
      baseDirectory.in(ThisBuild).value / "tests" / "mtest",
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

def isInTestShard(name: String) = {
  if (!isCI) {
    true
  } else {
    val groupIndex = TestGroups.testGroups.indexWhere(group => group(name))
    val groupId = Math.max(0, groupIndex) + 1
    System.getenv("TEST_SHARD").toInt == groupId
  }
}

lazy val unit = project
  .in(file("tests/unit"))
  .settings(
    testSettings,
    Test / testOptions := Seq(Tests.Filter(name => isInTestShard(name))),
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
    resourceGenerators
      .in(Compile) += InputProperties.resourceGenerator(input, input3),
    compile.in(Compile) :=
      compile
        .in(Compile)
        .dependsOn(
          compile.in(input, Test),
          compile.in(input3, Test)
        )
        .value,
    buildInfoKeys := Seq[BuildInfoKey](
      "sourceroot" -> baseDirectory.in(ThisBuild).value,
      "targetDirectory" -> target.in(Test).value,
      "testResourceDirectory" -> resourceDirectory.in(Test).value,
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
    testOnly
      .in(Test) := testOnly
      .in(Test)
      .dependsOn(publishLocal.in(`sbt-metals`), publishBinaryMtags)
      .evaluated,
    test.in(Test) := test
      .in(Test)
      .dependsOn(publishLocal.in(`sbt-metals`), publishBinaryMtags)
      .value
  )
  .dependsOn(unit)

lazy val bench = project
  .in(file("metals-bench"))
  .enablePlugins(BuildInfoPlugin)
  .settings(
    sharedSettings,
    fork.in(run) := true,
    skip.in(publish) := true,
    moduleName := "metals-bench",
    libraryDependencies ++= List(
      // for measuring memory usage
      "org.spire-math" %% "clouseau" % "0.2.2"
    ),
    buildInfoKeys := Seq[BuildInfoKey](scalaVersion),
    buildInfoPackage := "bench"
  )
  .dependsOn(unit)
  .enablePlugins(JmhPlugin)

lazy val docs = project
  .in(file("metals-docs"))
  .settings(
    sharedSettings,
    skip.in(publish) := true,
    moduleName := "metals-docs",
    mdoc := run.in(Compile).evaluated,
    munitRepository := Some("scalameta/metals"),
    libraryDependencies ++= List(
      "org.jsoup" % "jsoup" % "1.13.1"
    )
  )
  .dependsOn(metals)
  .enablePlugins(DocusaurusPlugin, MUnitReportPlugin)
