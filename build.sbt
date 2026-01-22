import scala.util.Random
import scala.collection.mutable
import scala.sys.process._
import Developers._
import Tests._

Global / onChangedBuildSource := ReloadOnSourceChanges

// For testing nightlies
Global / resolvers += "scala-integration" at
  "https://scala-ci.typesafe.com/artifactory/scala-integration/"

// The OSS version of Metals that this Databricks-internal fork is based on.
// Make sure to bump up this version when we merge with upstream.
val forkBaseVersion = "1.5.1"

def localSnapshotVersion = sys.env.getOrElse(
  "METALS_VERSION",
  s"$forkBaseVersion-${sys.env.getOrElse("METALS_VERSION_SUFFIX", "SNAPSHOT")}",
)
def isCI = System.getenv("CI") != null
def isTest = System.getenv("METALS_TEST") != null

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

ThisBuild / semanticdbVersion := V.semanticdb(scalaVersion.value)

inThisBuild(
  List(
    version ~= { dynVer =>
      if (isCI && !isTest) dynVer
      else localSnapshotVersion // only for local publishing
    },
    javaHome := Some(file(sys.env("JAVA_HOME"))),
    // semver does not like "+" in version numbers, especially if there are more than one
    dynverSeparator := "-",
    scalaVersion := V.scala213,
    crossScalaVersions := List(V.scala213),
    organization := "org.scalameta",
    licenses := Seq(
      "Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")
    ),
    homepage := Some(url("https://github.com/scalameta/metals")),
    developers := metalsDevs,
    testFrameworks := List(),
    // Sonatype OSSRH was sunset on 2025-06-30, use Central Portal
    resolvers += Resolver.sonatypeCentralSnapshots,
    versionScheme := Some("early-semver"),
    dependencyOverrides += V.guava,
    // faster publishLocal:
    packageDoc / publishArtifact := sys.env.contains("CI"),
    packageSrc / publishArtifact := sys.env.contains("CI"),
    PB.protocVersion := V.protobuf,
  )
)

Global / onLoad ~= { old =>
  if (!scala.util.Properties.isWin) {
    import java.nio.file._
    val prePush = Paths.get(".git", "hooks", "pre-push")
    // Skip hook installation if .git/hooks doesn't exist (e.g., in worktrees)
    if (Files.exists(prePush.getParent)) {
      Files.createDirectories(prePush.getParent)
      Files.write(
        prePush,
        """#!/bin/sh
          |set -eux
          |bin/scalafmt --diff --diff-branch main-v2
          |git diff --exit-code
          |""".stripMargin.getBytes(),
      )
      prePush.toFile.setExecutable(true)
    }
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
      mtagsShared / scalaVersion := scalaV,
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
    // Manually clean input because we rely on `unmanagedJars +=` to enable
    // the semanticdb-javac compiler plugin, and it doesn't pick up changes
    // in the plugin codebase otherwise.
    "semanticdb-javac/package" :: "input/clean" :: "input/compile" ::
      "unit/test:runMain tests.SaveExpect" ::
      s
  },
  Command.command("quick-publish-local") { s =>
    val publishMtags = V.quickPublishScalaVersions.foldLeft(s) { case (st, v) =>
      runMtagsPublishLocal(st, v, localSnapshotVersion)
    }
    "interfaces/publishLocal" ::
      "jsemanticdb/publishLocal" ::
      "turbine/publishLocal" ::
      "semanticdb-javac/publishLocal" ::
      s"++${V.scala213} metals/publishLocal" ::
      "mtags-java/publishLocal" ::
      publishMtags
  },
  Command.single("test-mtags-dyn") { (s, scalaV) =>
    crossTestDyn(s, scalaV)
  },
)

// -Xlint is unusable because of
// https://github.com/scala/bug/issues/10448
def lintingOptions(scalaVersion: String) = {
  val unused213 = "-Wunused"
  val unused3 = "-Wunused:all"
  val common = List(
    "-Xsource:3",
    // desugaring of for yield caused pattern var to complain
    // https://github.com/scala/bug/issues/10287
    "-Wconf:msg=in anonymous function is never used:silent",
    // silence unused parameters in mtags in abstract methods
    "-Wconf:src=*.BasePCSuite.scala&msg=parameter (scalaVersion|classpath) in method (extraDependencies|scalacOptions):silent",
    "-Wconf:src=*.CodeLens.scala&msg=parameter (textDocumentWithPath|path) in method codeLenses is never used:silent",
    "-Wconf:src=*.Completions.scala&msg=parameter (member|m) in method (isCandidate|isPrioritized):silent",
    "-Wconf:src=*.JavaMtags.scala&msg=parameter (ctor|method) in method (visitConstructor|visitMethod):silent",
    "-Wconf:src=*.MtagsIndexer.scala&msg=parameter owner in method visitOccurrence:silent",
    "-Wconf:src=*.ScaladocParser.scala&msg=parameter (pos|message) in method reportError:silent",
    "-Wconf:src=*.TreeViewProvider.scala&msg=parameter params in method (children|parent) is never used:silent",
    "-Wconf:src=*.InheritanceContext.scala&msg=parameter ec in method getLocations is never used:silent",
    "-Wconf:src=*.CompilerWrapper.scala&msg=parameter params in method compiler is never used:silent",
    // silence "The outer reference in this type test cannot be checked at run time."
    "-Wconf:src=.*(CompletionProvider|ArgCompletions|Completions|Keywords|IndentOnPaste).scala&msg=The outer reference:silent",
  )
  crossSetting(
    scalaVersion,
    if213 = unused213 :: common,
    if3 = unused3 :: Nil,
    if211 = List("-Ywarn-unused-import"),
  )
}

val sharedJavaOptions = Seq(
  "-Djol.magicFieldOffset=true", "-Djol.tryWithSudo=true",
  "-Djdk.attach.allowAttachSelf", "--add-opens=java.base/java.nio=ALL-UNNAMED",
  "--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
  "--add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
  "--add-exports=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED",
  "--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
  "--add-exports=jdk.compiler/com.sun.tools.javac.jvm=ALL-UNNAMED",
  "--add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
  "--add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED",
  "--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
  "--add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED",
  "--add-exports=jdk.compiler/com.sun.tools.javac.resources=ALL-UNNAMED",
  "--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
  "--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
  "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
  "--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
  "--add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED",
  "--add-opens=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
  "--add-opens=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
  // Ensure that all JVM-internal logging goes to stderr to avoid breaking the
  // LSP protocol
  "-XX:+DisplayVMOutputToStderr", "-Xlog:disable",
  "-Xlog:all=warning,gc=warning:stderr",
)

val sharedScalacOptions = List(
  scalacOptions ++= {
    CrossVersion.partialVersion(scalaVersion.value) match {
      //  Scala 2.12 and 2.11 cannot output for JDKs > 8
      case partialVersion if isScala211(partialVersion) =>
        List("-target:jvm-1.8", "-Yrangepos", "-Xexperimental")
      case partialVersion if isScala212(partialVersion) =>
        List("-Yrangepos", "-Xexperimental")
      case partialVersion if isScala3(partialVersion) =>
        List("-Xtarget:17", "-language:implicitConversions", "-Xsemanticdb")
      case _ =>
        List("-target:17", "-Yrangepos")
    }
  }
)

val sharedSettings = sharedScalacOptions ++ List(
  Compile / doc / sources := Seq.empty,
  libraryDependencies ++= crossSetting(
    scalaVersion.value,
    if2 = List(
      compilerPlugin(
        "org.scalameta" % "semanticdb-scalac" % V.semanticdb(
          scalaVersion.value
        ) cross CrossVersion.full
      )
    ),
  ),
  scalacOptions ++= lintingOptions(scalaVersion.value),
)

publish / skip := true

lazy val interfaces = project
  .in(file("mtags-interfaces"))
  .settings(
    moduleName := "mtags-interfaces",
    autoScalaLibrary := false,
    mimaPreviousArtifacts := Set(
      "org.scalameta" % "mtags-interfaces" % "1.2.2",
      "org.scalameta" % "mtags-interfaces" % "1.3.2",
      "org.scalameta" % "mtags-interfaces" % "1.4.2",
    ),
    crossPaths := false,
    libraryDependencies ++= List(
      "org.slf4j" % "slf4j-api" % "1.7.36",
      V.lsp4j,
    ),
    javacOptions := Seq("--release", "8"),
    crossVersion := CrossVersion.disabled,
    Compile / doc / javacOptions ++= List(
      "-tag",
      "implNote:a:Implementation Note:",
    ),
  )

lazy val turbine = project
  .in(file("vendor/turbine"))
  .settings(sharedSettings)
  .settings(
    moduleName := "turbine",
    Compile / packageSrc / publishArtifact := true,
    autoScalaLibrary := false,
    crossPaths := false,
    // Must set Java home to fork on compile and see errors in sbt compile
    crossVersion := CrossVersion.disabled,
    Compile / fullClasspath := Nil,
    libraryDependencies ++= List(
      V.guava,
      "com.google.auto.value" % "auto-value" % "1.11.1",
      "com.google.auto.value" % "auto-value-annotations" % "1.11.1",
      "com.google.protobuf" % "protobuf-java" % V.protobuf,
    ),
    (Compile / PB.targets) :=
      Seq(PB.gens.java(V.protobuf) -> (Compile / sourceManaged).value),
  )
lazy val jsemanticdb = project
  .in(file("jsemanticdb"))
  .settings(sharedSettings)
  .settings(
    moduleName := "jsemanticdb",
    Compile / packageSrc / publishArtifact := true,
    autoScalaLibrary := false,
    crossPaths := false,
    // Must set Java home to fork on compile and see errors in sbt compile
    crossVersion := CrossVersion.disabled,
    Compile / fullClasspath := Nil,
    libraryDependencies ++= List(
      "org.slf4j" % "slf4j-api" % "1.7.36",
      "com.google.protobuf" % "protobuf-java-util" % V.protobuf,
      "com.google.protobuf" % "protobuf-java" % V.protobuf,
    ),
    (Compile / PB.targets) :=
      Seq(PB.gens.java(V.protobuf) -> (Compile / sourceManaged).value),
  )

lazy val mtagsShared = project
  .in(file("mtags-shared"))
  .settings(sharedSettings)
  .settings(
    moduleName := "mtags-shared",
    crossTarget := target.value / s"scala-${scalaVersion.value}",
    // Dotty depends on Scala 2.13 for compatibility guarantees for from-source compilation.
    crossScalaVersions := V.supportedScalaVersions,
    scalacOptions --= crossSetting(
      scalaVersion.value,
      if213 = List("-target:17"),
    ),
    scalacOptions ++= crossSetting(
      scalaVersion.value,
      if213 = List("-target:8"),
    ),
    crossVersion := CrossVersion.full,
    libraryDependencies ++= pprintDebuggingDependency,
    Compile / packageSrc / publishArtifact := true,
    Compile / scalacOptions ++= {
      if (scalaVersion.value == V.lastPublishedScala3)
        List("-Yexplicit-nulls", "-language:unsafeNulls")
      else Nil
    },
    libraryDependencies ++= List(
      "com.google.protobuf" % "protobuf-java-util" % V.protobuf,
      "com.google.protobuf" % "protobuf-java" % V.protobuf,
      V.guava,
      "io.get-coursier" % "interface" % V.coursierInterfaces,
      "org.lz4" % "lz4-java" % "1.8.0",
      "org.slf4j" % "slf4j-api" % "1.7.36",
    ),
    (Compile / PB.targets) :=
      Seq(PB.gens.java(V.protobuf) -> (Compile / sourceManaged).value),
  )
  .dependsOn(interfaces, jsemanticdb)

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

def withExcludes(moduleId: ModuleID) =
  moduleId
    .exclude("org.scala-lang", "scala-reflect")
    .exclude("org.scala-lang", "scala-compiler")
    // the correct one should be brought in by the scala 3 compiler
    .exclude("org.scala-lang", "scala-library")
    .exclude(
      "com.lihaoyi",
      "geny_2.13",
    ) // avoid 2.13 and 3 on the classpath since we rely on it directly
    .exclude(
      "com.lihaoyi",
      "sourcecode_2.13",
    ) // avoid 2.13 and 3 on the classpath since it comes in via pprint

def scala3SemanticdbDependency: ModuleID = withExcludes(
  ("org.scalameta" %% s"semanticdb-shared" % V.scalameta)
    .cross(CrossVersion.for3Use2_13)
)

def scala3ScalametaDependency: ModuleID = withExcludes(
  ("org.scalameta" %% "scalameta" % V.scalameta)
    .cross(CrossVersion.for3Use2_13)
)

val mtagsSettings = List(
  crossScalaVersions := V.supportedScalaVersions,
  crossTarget := target.value / s"scala-${scalaVersion.value}",
  crossVersion := CrossVersion.full,
  Compile / unmanagedSourceDirectories ++= multiScalaDirectories(
    (ThisBuild / baseDirectory).value / "mtags",
    scalaVersion.value,
  ),
  libraryDependencies ++= Seq(
    "org.ow2.asm" % "asm" % "9.9",
    "com.lihaoyi" %% "geny" % V.genyVersion,
    "com.thoughtworks.qdox" % "qdox" % V.qdox, // for java mtags
    "org.scala-lang.modules" %% "scala-java8-compat" % V.java8Compat,
    "org.jsoup" % "jsoup" % V.jsoup, // for extracting HTML from javadocs
    // for ivy completions
    "io.get-coursier" % "interface" % V.coursierInterfaces,
    "org.lz4" % "lz4-java" % "1.8.0",
  ),
  libraryDependencies ++= {
    crossSetting(
      scalaVersion.value,
      if2 = List(
        // for token edit-distance used by goto definition
        "com.googlecode.java-diff-utils" % "diffutils" % "1.3.0",
        "org.scalameta" % "semanticdb-scalac-core" % V.semanticdb(
          scalaVersion.value
        ) cross CrossVersion.full,
      ),
      if3 = List(
        "org.scala-lang" %% "scala3-compiler" % scalaVersion.value,
        scala3ScalametaDependency,
        scala3SemanticdbDependency,
      ),
    )
  },
  libraryDependencies ++= pprintDebuggingDependency,
  buildInfoPackage := "scala.meta.internal.mtags",
  buildInfoKeys := Seq[BuildInfoKey](
    "scalaCompilerVersion" -> scalaVersion.value
  ),
  Compile / unmanagedSourceDirectories := {
    val current = (Compile / unmanagedSourceDirectories).value
    val base = (Compile / sourceDirectory).value
    val regex = "(\\d+)\\.(\\d+)\\.(\\d+).*".r
    // For scala -2.13.9 we need to have a special Compat.scala
    // For this case filter out `scala-2.13` directory that comes by default
    val scalaVersionsWithSpecialCompat =
      Set("2.13.5", "2.13.6", "2.13.7", "2.13.8")
    if (scalaVersionsWithSpecialCompat(scalaVersion.value))
      current.filter(f => f.getName() != "scala-2.13")
    else
      current
  },
)

val pprintDebuggingDependency: List[ModuleID] =
  if (isCI) Nil
  // NOTE(olafur) pprint is indispensable for me while developing, I can't
  // use println anymore for debugging because pprint.log is 100 times better.
  else List("com.lihaoyi" %% "pprint" % V.pprint)

lazy val mtags = project
  .settings(
    sharedSettings,
    mtagsSettings,
    moduleName := "mtags",
    projectDependencies := projectDependencies.value,
  )
  .dependsOn(mtagsShared)
  .enablePlugins(BuildInfoPlugin)

val toolchainJavaOptions = List(
  "--add-modules",
  "jdk.compiler",
) ++ sharedJavaOptions.filter(_.startsWith("--add-exports="))
lazy val `java-header-compiler` = project
  .settings(
    moduleName := "java-header-compiler",
    autoScalaLibrary := false,
    crossPaths := false,
    // Must set Java home to fork on compile and see errors in sbt compile
    crossVersion := CrossVersion.disabled,
    Compile / fullClasspath := Nil,
    // For some reason, need the same options for java and javac to pass compilation
    Compile / javaOptions ++= toolchainJavaOptions,
    Compile / javacOptions ++= toolchainJavaOptions,
    Compile / packageBin / mappings ++= {
      // Add the META-INF/services/com.sun.source.util.Plugin file to the JAR only
      // during packaging to avoid errors during incremental compilation where
      // resources are added to the compile-classpath causing javac to fail because
      // the plugin itself is not compiled yet. Adding -proc:none didn't help.
      val resourceDir = (Compile / sourceDirectory).value / "resources-packaged"
      (resourceDir.allPaths.get() pair Path.relativeTo(resourceDir)).map {
        case (file, path) => file -> path
      }
    },
  )
  .disablePlugins(ScalafixPlugin)
  // For some strange reason, it fails to build because it's missing
  // --add-export options
  .disablePlugins(BloopPlugin)

lazy val `semanticdb-javac` = project
  .settings(
    moduleName := "semanticdb-javac",
    autoScalaLibrary := false,
    crossPaths := false,
    // Must set Java home to fork on compile and see errors in sbt compile
    crossVersion := CrossVersion.disabled,
    Compile / fullClasspath := Nil,
    // For some reason, need the same options for java and javac to pass compilation
    Compile / javaOptions ++= toolchainJavaOptions,
    Compile / javacOptions ++= toolchainJavaOptions,
    Compile / packageBin / mappings ++= {
      // Add the META-INF/services/com.sun.source.util.Plugin file to the JAR only
      // during packaging to avoid errors during incremental compilation where
      // resources are added to the compile-classpath causing javac to fail because
      // the plugin itself is not compiled yet. Adding -proc:none didn't help.
      val resourceDir = (Compile / sourceDirectory).value / "resources-packaged"
      (resourceDir.allPaths.get() pair Path.relativeTo(resourceDir)).map {
        case (file, path) => file -> path
      }
    },
  )
  .dependsOn(jsemanticdb)
  .disablePlugins(ScalafixPlugin)

lazy val `mtags-java` = project
  .settings(
    libraryDependencies ++= pprintDebuggingDependency,
    Compile / javaOptions ++= toolchainJavaOptions,
    Compile / javacOptions ++= toolchainJavaOptions,
    Compile / javacOptions ++= sharedJavaOptions.map(o => s"-J$o"),
    Compile / javacOptions ++= List("-Xlint:deprecation"),
  )
  .configure(JavaPcSettings.settings(sharedSettings))
  .dependsOn(interfaces, mtagsShared, `semanticdb-javac`, turbine)

lazy val metals = project
  .settings(
    sharedSettings,
    Compile / run / fork := true,
    Compile / run / javaOptions ++= sharedJavaOptions,
    Compile / mainClass := Some("scala.meta.metals.Main"),
    // for Java formatting
    libraryDependencies ++= V.eclipseJdt,
    // As a general rule of thumb, we try to keep Scala dependencies to a minimum.
    libraryDependencies ++= List(
      // =================
      // Java dependencies
      // =================
      // for bloom filters
      "com.google.code.findbugs" % "jsr305" % "3.0.2", // for nullability annotations
      V.guava,
      "org.slf4j" % "slf4j-api" % "1.7.36",
      "org.scalameta" %% "metaconfig-core" % "0.14.0",
      // for measuring memory footprint
      "org.openjdk.jol" % "jol-core" % "0.17",
      // for file watching
      "com.swoval" % "file-tree-views" % "2.1.12",
      // for http client
      "io.undertow" % "undertow-core" % "2.2.20.Final",
      "org.jboss.xnio" % "xnio-nio" % "3.8.16.Final",
      // for persistent data like "dismissed notification"
      "org.flywaydb" % "flyway-core" % "11.2.0",
      "com.h2database" % "h2" % "2.3.232",
      // for BSP
      "org.scala-sbt.ipcsocket" % "ipcsocket" % "1.6.3",
      "ch.epfl.scala" % "bsp4j" % V.bsp,
      "ch.epfl.scala" %% "bloop-rifle" % V.bloop,
      // for LSP
      V.lsp4j,
      // for DAP
      V.dap4j,
      "ch.epfl.scala" %% "scala-debug-adapter" % V.debugAdapter,
      // for finding paths of global log/cache directories
      "dev.dirs" % "directories" % "26",
      // for decompiling Java code
      "org.benf" % "cfr" % "0.152",

      // ==============================================
      // Databricks dependencies for inlined telemetry.
      // These should not get upstreamed.
      // ==============================================
      "com.lihaoyi" %% "os-lib" % "0.11.5",
      "com.lihaoyi" %% "upickle" % "4.3.2",
      "com.lihaoyi" %% "requests" % "0.9.0",

      // ==================
      // Scala dependencies
      // ==================
      "org.scalameta" % "mdoc-interfaces" % V.mdoc,
      "org.scalameta" %% "scalafmt-dynamic" % V.scalafmt,
      "ch.epfl.scala" % "scalafix-interfaces" % V.scalafix,

      // For reading classpaths.
      // for fetching ch.epfl.scala:bloop-frontend and other library dependencies
      "io.get-coursier" % "interface" % V.coursierInterfaces,
      // for comparing versions && fetching from sbt maven repository
      "io.get-coursier" %% "coursier" % V.coursier,
      "io.get-coursier" %% "coursier-sbt-maven-repository" % V.coursier,
      // for logging
      "com.outr" %% "scribe" % V.scribe,
      "com.outr" %% "scribe-file" % V.scribe,
      "com.outr" %% "scribe-slf4j2" % V.scribe, // needed for flyway database migrations
      // for JSON formatted doctor
      "com.lihaoyi" %% "ujson" % "4.1.0",
      // For fetching projects' templates
      "com.lihaoyi" %% "requests" % "0.9.0",
      // for producing SemanticDB from Scala source files, to be sure we want the same version of scalameta
      "org.scalameta" %% "scalameta" % V.semanticdb(scalaVersion.value),
      "org.scalameta" %% "semanticdb-metap" % V.semanticdb(
        scalaVersion.value
      ) cross CrossVersion.full,
      "org.scalameta" %% "semanticdb-shared" % V.semanticdb(scalaVersion.value),
      // For starting Ammonite
      "io.github.alexarchambault.ammonite" %% "ammonite-runner" % "0.4.0",
      "org.scala-lang.modules" %% "scala-xml" % "2.3.0",
      ("org.virtuslab.scala-cli" % "scala-cli-bsp" % V.scalaCli)
        .exclude("ch.epfl.scala", "bsp4j"),
      "com.google.googlejavaformat" % "google-java-format" % "1.28.0",
    ),
    Compile / resourceGenerators += packageJavaHeaderCompiler,
    Compile / resourceGenerators += Def.task {
      val file =
        (Compile / managedResourceDirectories).value.head / "META-INF" / "metals-required-vm-options.txt"
      IO.write(file, sharedJavaOptions.mkString("\n"))
      Seq(file)
    },
    buildInfoPackage := "scala.meta.internal.metals",
    buildInfoKeys := Seq[BuildInfoKey](
      "localSnapshotVersion" -> localSnapshotVersion,
      "metalsVersion" -> version.value,
      // Databricks-only: Use the latest matching OSS sbt-metals version. Our
      // internal release of sbt-metals is never going to work out of the box
      // without adding the s3 resolver to sbt's classpath. It's much simpler to
      // just use an older sbt-metals instead.
      "sbtMetalsVersion" -> forkBaseVersion,
      "mdocVersion" -> V.mdoc,
      "bspVersion" -> V.bsp,
      "sbtVersion" -> sbtVersion.value,
      "bloopVersion" -> V.bloop,
      "bloopConfigVersion" -> V.bloopConfig,
      "bloopNightlyVersion" -> V.bloop,
      "sbtBloopVersion" -> V.sbtBloop,
      "gitter8Version" -> V.gitter8Version,
      "gradleBloopVersion" -> V.gradleBloop,
      "mavenBloopVersion" -> V.mavenBloop,
      "scalametaVersion" -> V.scalameta,
      "semanticdbVersion" -> V.semanticdb(scalaVersion.value),
      "javaSemanticdbVersion" -> V.javaSemanticdb,
      "scalafmtVersion" -> V.scalafmt,
      "ammoniteVersion" -> V.ammonite,
      "scalaCliVersion" -> V.scalaCli,
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
      "bazelScalaVersion" -> V.bazelScalaVersion,
      "scala213" -> V.scala213,
      "scala3" -> V.scala3,
      "lastSupportedSemanticdb" -> SemanticDbSupport.last,
    ),
  )
  .dependsOn(mtags, `mtags-java`)
  .enablePlugins(BuildInfoPlugin)

lazy val `sbt-metals` = project
  .settings(
    buildInfoPackage := "scala.meta.internal.sbtmetals",
    buildInfoKeys := Seq[BuildInfoKey](
      "semanticdbVersion" -> V.semanticdb(scalaVersion.value),
      "supportedScala2Versions" -> V.scala2Versions,
      "javaSemanticdbVersion" -> V.javaSemanticdb,
      "lastSupportedSemanticdb" -> SemanticDbSupport.last,
    ),
    scalaVersion := V.scala212,
    crossScalaVersions := Seq(V.scala212, V.scala3ForSBT2),
    scalacOptions := Seq("-release", "8"),
    scriptedLaunchOpts ++= Seq(s"-Dplugin.version=${version.value}"),
    (pluginCrossBuild / sbtVersion) := {
      scalaBinaryVersion.value match {
        case "2.12" => "1.5.8"
        case _ => "2.0.0-M3"
      }
    },
    scalacOptions ++= {
      scalaBinaryVersion.value match {
        case "2.12" => "-Xsource:3" :: Nil
        case _ => Nil
      }
    },
  )
  .settings(sharedScalacOptions)
  .enablePlugins(BuildInfoPlugin, SbtPlugin)
  .disablePlugins(ScalafixPlugin)

lazy val input = project
  .in(file("tests/input"))
  .settings(
    sharedSettings,
    scalacOptions -= "-Xsource:3",
    publish / skip := true,
    Compile / unmanagedJars ++= List(
      // Normally, we would be able to `.dependsOn(semanticdb-javac)`, but it
      // doesn't work because the project doesn't have a normal resource file
      // META-INF/services/com.sun.source.util.Plugin, we add it only during `package`
      // to work around a limitation in zinc incremental compilation where javac crashes
      // when compiling the semanticdb-javac project with the plugin itself.
      (`semanticdb-javac` / Compile / Keys.`package`).value
    ),
    libraryDependencies ++= List(
      "org.slf4j" % "slf4j-api" % "1.7.36",
      // these projects have macro annotations
      "org.scalameta" %% "scalameta" % V.scalameta,
      "io.circe" %% "circe-derivation-annotations" % "0.13.0-M5",
    ),
    javacOptions += s"-Xplugin:MetalsSemanticdb -sourceroot:${(ThisBuild / baseDirectory).value} -targetroot:${(Compile / classDirectory).value}",
    scalacOptions ++= Seq("-P:semanticdb:synthetics:on", "-Ymacro-annotations"),
    scalacOptions ~= { options =>
      options.filter(!_.contains("-Wunused"))
    },
  )
  .dependsOn(jsemanticdb)
  .disablePlugins(ScalafixPlugin)

lazy val input3 = project
  .in(file("tests/input3"))
  .settings(
    sharedSettings,
    scalaVersion := V.scala3,
    target := (ThisBuild / baseDirectory).value / "tests" / "input" / "target" / "target3",
    Compile / unmanagedSourceDirectories := Seq(
      (input / baseDirectory).value / "src" / "main" / "scala",
      (input / baseDirectory).value / "src" / "main" / "scala-3",
      (input / baseDirectory).value / "src" / "main" / "java",
    ),
    scalaVersion := V.scala3,
    publish / skip := true,
    scalacOptions ~= { options =>
      options.filter(!_.contains("-Wunused"))
    },
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
  Test / javaOptions ++= toolchainJavaOptions,
  Test / javaOptions ++= sharedJavaOptions,
  Test / javaOptions ++= Seq(
    "-XX:-OmitStackTraceInFastThrow", "-Dmetals.telemetry=disabled",
    "-Dmetals.env=testing", "-Xmx16G", "-XX:+HeapDumpOnOutOfMemoryError",
    "-XX:+ExitOnOutOfMemoryError",
  ),
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
        mtagsShared / scalaVersion := scalaV,
        mtags / scalaVersion := scalaV,
        ThisBuild / version := projectV,
        ThisBuild / useSuperShell := false,
      ),
      state,
    )
  val (s1, _) = Project
    .extract(newState)
    .runTask(mtagsShared / publishLocal, newState)

  val (s2, _) = Project
    .extract(s1)
    .runTask(mtags / publishLocal, s1)
  s2
}

def runMtagsPublishGhPackages(
    state: State,
    scalaV: String,
): State = {
  val newState = Project
    .extract(state)
    .appendWithSession(
      List(
        mtagsShared / scalaVersion := scalaV,
        mtags / scalaVersion := scalaV,
        ThisBuild / useSuperShell := false,
      ),
      state,
    )
  val (s1, _) = Project
    .extract(newState)
    .runTask(mtagsShared / publish, newState)

  val (s2, _) = Project
    .extract(s1)
    .runTask(mtags / publish, s1)
  s2
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
      `mtags-java` / publishLocal,
      publishAllMtags(V.quickPublishScalaVersions),
    )

lazy val mtest = project
  .in(file("tests/mtest"))
  .settings(
    testSettings,
    sharedSettings,
    libraryDependencies ++=
      List(
        "org.scalameta" %% "munit" % {
          if (scalaVersion.value.startsWith("2.11")) "1.0.0-M10"
          else if (scalaVersion.value == "2.13.14") "1.0.2"
          else if (scalaVersion.value == "2.13.13") "1.0.0"
          else if (scalaVersion.value == "2.13.12") "1.0.0-M11"
          else if (scalaVersion.value == "2.13.11") "1.0.0-M10"
          else V.munit
        },
        "com.outr" %% "scribe" % V.scribe,
        "com.outr" %% "scribe-slf4j2" % V.scribe,
        "io.get-coursier" % "interface" % V.coursierInterfaces,
      ),
    dependencyOverrides += "org.scala-lang" % "scala-library" % scalaVersion.value,
    buildInfoPackage := "tests",
    buildInfoObject := "BuildInfoVersions",
    buildInfoKeys := Seq[BuildInfoKey](
//      "scala211" -> V.scala211,
      "scala212" -> V.scala212,
      "scala213" -> V.scala213,
      "scala3" -> V.scala3,
      "scala2Versions" -> V.scala2Versions,
      "scala3Versions" -> V.scala3Versions,
      "scala2Versions" -> V.scala2Versions,
      "scalaVersion" -> scalaVersion.value,
      "kindProjector" -> V.kindProjector,
      "betterMonadicFor" -> V.betterMonadicFor,
      "lastSupportedSemanticdb" -> SemanticDbSupport.last,
    ),
    crossScalaVersions := V.nonDeprecatedScalaVersions,
    Compile / unmanagedSourceDirectories ++= multiScalaDirectories(
      (ThisBuild / baseDirectory).value / "tests" / "mtest",
      scalaVersion.value,
    ),
  )
  .dependsOn(mtags, `semanticdb-javac`)
  .enablePlugins(BuildInfoPlugin)

lazy val cross = project
  .in(file("tests/cross"))
  .settings(
    testSettings,
    sharedSettings,
    crossScalaVersions := V.nonDeprecatedScalaVersions,
  )
  .dependsOn(mtest)

// This plugin is ~5kb with no external dependencies. It only uses javac APIs to
// wipe out method bodies and field initializers.
lazy val packageJavaHeaderCompiler = Def.task {
  val javaHeaderCompilerJar =
    (`java-header-compiler` / Compile / Keys.`package`).value
  val file =
    (Compile / managedResourceDirectories).value.head / "java-header-compiler.jar"
  IO.copyFile(javaHeaderCompilerJar, file)
  Seq(file)
}

lazy val javapc = project
  .in(file("tests/javapc"))
  .settings(
    testSettings,
    sharedSettings,
    libraryDependencies ++= List(
      "com.outr" %% "scribe" % V.scribe,
      "com.outr" %% "scribe-slf4j2" % V.scribe,
    ),
    Compile / resourceGenerators += packageJavaHeaderCompiler,
  )
  .dependsOn(mtest, `mtags-java`)

def isInTestShard(name: String): Boolean = {
  (
    Option(System.getenv("TEST_SHARD_COUNT")),
    Option(System.getenv("TEST_SHARD")),
  ) match {
    case (Some(shardCount), Some(oneBasedTestShard)) =>
      val testShard = oneBasedTestShard.toInt - 1
      val nameShard = new Random(name.hashCode).nextInt(shardCount.toInt)
      nameShard == testShard
    case _ =>
      if (isCI) {
        throw new Exception(
          s"TEST_SHARD_COUNT and TEST_SHARD must be set when running in CI."
        )
      }
      true
  }
}

lazy val metalsDependencies = project
  .in(file("target/.dependencies"))
  .settings(
    publish / skip := true,
    // silent the intransitive dependency warning
    publishMavenStyle := false,
    libraryDependencies ++= List(
      // The dependencies listed below are only listed so Scala Steward
      // will pick them up and update them. They aren't actually used.
      "com.lihaoyi" %% "ammonite-util" % V.ammonite,
      // not available for Scala 2.13.13
      // "org.typelevel" % "kind-projector" % V.kindProjector cross CrossVersion.full,
      "com.olegpy" %% "better-monadic-for" % V.betterMonadicFor,
      "com.lihaoyi" % "mill-contrib-testng" % V.mill,
      "org.virtuslab.scala-cli" % "cli_3" % V.scalaCli intransitive (),
      "ch.epfl.scala" % "bloop-maven-plugin" % V.mavenBloop,
      "ch.epfl.scala" %% "gradle-bloop" % V.gradleBloop,
      "com.sourcegraph" % "semanticdb-java" % V.javaSemanticdb,
      "org.foundweekends.giter8" %% "giter8" % V.gitter8Version intransitive (),
    ),
  )
  .disablePlugins(ScalafixPlugin)

lazy val unit = project
  .in(file("tests/unit"))
  .settings(
    testSettings,
    Test / testOptions ++= Seq(
      Tests.Filter(name => isInTestShard(name))
    ),
    sharedSettings,
    libraryDependencies ++= List(
      "io.get-coursier" %% "coursier" % V.coursier, // for jars
      "ch.epfl.scala" %% "bloop-config" % V.bloopConfig,
      "org.scalameta" %% "munit" % V.munit,
    ),
    buildInfoPackage := "tests",
    Compile / resourceGenerators += InputProperties
      .resourceGenerator(input, input3),
    Compile / compile := (Compile / compile)
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
    libraryDependencies ++= List(
      "tools.profiler" % "async-profiler" % "4.2",
      "tools.profiler" % "jfr-converter" % "4.2",
    ),
    Jmh / bspEnabled := false,
    Jmh / fork := true,
    Jmh / javaOptions ++= sharedJavaOptions,
    Jmh / javaOptions +=
      s"-Dmetals.jfr.dir=${(ThisBuild / baseDirectory).value / "target"}",
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
    dependencyOverrides += "org.scalameta" %% "metaconfig-core" % "0.14.0",
  )
  .dependsOn(metals)
  .enablePlugins(DocusaurusPlugin)
