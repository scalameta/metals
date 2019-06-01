def localSnapshotVersion = "0.6.0-SNAPSHOT"
def isCI = System.getenv("CI") != null
inThisBuild(
  List(
    version ~= { dynVer =>
      if (isCI) dynVer
      else localSnapshotVersion // only for local publishng
    },
    scalaVersion := V.scala212,
    crossScalaVersions := List(V.scala212),
    scalacOptions ++= List(
      "-target:jvm-1.8",
      "-Yrangepos",
      "-deprecation",
      // -Xlint is unusable because of
      // https://github.com/scala/bug/issues/10448
      "-Ywarn-unused-import"
    ),
    addCompilerPlugin(
      "org.scalameta" % "semanticdb-scalac" % V.scalameta cross CrossVersion.full
    ),
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
    resolvers += Resolver.sonatypeRepo("releases"),
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
        .map { configKey =>
          s"-D$configKey=${props.getProperty(configKey)}"
        }
        .mkString(" ")
    },
    resolvers += Resolver.bintrayRepo("scalacenter", "releases")
  )
)

cancelable.in(Global) := true
crossScalaVersions := Nil

addCommandAlias("scalafixAll", "all compile:scalafix test:scalafix")
addCommandAlias("scalafixCheck", "; scalafix --check ; test:scalafix --check")

commands += Command.command("save-expect") { s =>
  "unit/test:runMain tests.SaveExpect" ::
    s
}

lazy val V = new {
  val scala210 = "2.10.7"
  val scala211 = "2.11.12"
  val scala212 = "2.12.8"
  val scalameta = "4.1.9"
  val semanticdb = scalameta
  val bsp = "2.0.0-M3"
  val bloop = "1.2.5"
  val sbtBloop = bloop
  val gradleBloop = "1.2.5+271-7c4a6e6a"
  val scalafmt = "2.0.0-RC4"
  // List of supported Scala versions in SemanticDB. Needs to be manually updated
  // for every SemanticDB upgrade.
  def supportedScalaVersions =
    Seq("2.12.8", "2.12.7", "2.11.12") ++ deprecatedScalaVersions
  def deprecatedScalaVersions = Seq[String](
    "2.12.6", "2.12.5", "2.12.4", "2.11.11", "2.11.10", "2.11.9"
  )
}

skip.in(publish) := true

lazy val interfaces = project
  .in(file("mtags-interfaces"))
  .settings(
    moduleName := "mtags-interfaces",
    autoScalaLibrary := false,
    libraryDependencies ++= List(
      "org.eclipse.lsp4j" % "org.eclipse.lsp4j" % "0.7.1"
    ),
    crossVersion := CrossVersion.disabled
  )

lazy val mtags = project
  .settings(
    moduleName := "mtags",
    crossVersion := CrossVersion.full,
    crossScalaVersions := V.supportedScalaVersions,
    scalacOptions ++= List(
      // Needed for SAM types on 2.11.
      "-Xexperimental"
    ),
    libraryDependencies ++= List(
      "com.thoughtworks.qdox" % "qdox" % "2.0-M9", // for java mtags
      "org.jsoup" % "jsoup" % "1.11.3",
      "org.scalameta" % "semanticdb-scalac-core" % V.scalameta cross CrossVersion.full
    ),
    libraryDependencies ++= {
      if (isCI) Nil
      // NOTE(olafur) pprint is indispensable for me while developing, I can't
      // use println anymore for debugging because pprint.log is 100 times better.
      else List("com.lihaoyi" %% "pprint" % "0.5.3")
    }
  )
  .dependsOn(interfaces)

lazy val metals = project
  .settings(
    fork.in(Compile, run) := true,
    // As a general rule of thumb, we try to keep Scala dependencies to a minimum.
    libraryDependencies ++= List(
      // =================
      // Java dependencies
      // =================
      // for bloom filters
      "com.google.guava" % "guava" % "27.0.1-jre",
      // for measuring memory footprint
      "org.openjdk.jol" % "jol-core" % "0.9",
      // for file watching
      "io.methvin" % "directory-watcher" % "0.8.0",
      // for http client
      "io.undertow" % "undertow-core" % "2.0.13.Final",
      "org.jboss.xnio" % "xnio-nio" % "3.6.5.Final",
      // for persistent data like "dismissed notification"
      "org.flywaydb" % "flyway-core" % "5.2.1",
      "com.h2database" % "h2" % "1.4.197",
      // for starting `sbt bloopInstall` process
      "com.zaxxer" % "nuprocess" % "1.2.4",
      "net.java.dev.jna" % "jna" % "4.5.1",
      "net.java.dev.jna" % "jna-platform" % "4.5.1",
      // for token edit-distance used by goto definition
      "com.googlecode.java-diff-utils" % "diffutils" % "1.3.0",
      // for BSP
      "org.scala-sbt.ipcsocket" % "ipcsocket" % "1.0.0",
      "ch.epfl.scala" % "bsp4j" % V.bsp,
      // for LSP
      "org.eclipse.lsp4j" % "org.eclipse.lsp4j" % "0.5.0",
      // for producing SemanticDB from Java source files
      "com.thoughtworks.qdox" % "qdox" % "2.0-M9",
      // for finding paths of global log/cache directories
      "io.github.soc" % "directories" % "11",
      // ==================
      // Scala dependencies
      // ==================
      "org.scala-lang.modules" %% "scala-java8-compat" % "0.9.0",
      "org.scalameta" %% "scalafmt-dynamic" % V.scalafmt,
      // For reading classpaths.
      // for fetching ch.epfl.scala:bloop-frontend and other library dependencies
      "com.geirsson" %% "coursier-small" % "1.3.3",
      // undeclared transitive dependency of coursier-small
      "org.scala-lang.modules" %% "scala-xml" % "1.1.1",
      // for logging
      "com.outr" %% "scribe" % "2.6.0",
      "com.outr" %% "scribe-slf4j" % "2.6.0", // needed for flyway database migrations
      // for debugging purposes, not strictly needed but nice for productivity
      "com.lihaoyi" %% "pprint" % "0.5.3",
      // for producing SemanticDB from Scala source files
      "org.scalameta" %% "scalameta" % V.scalameta,
      "org.scalameta" % "semanticdb-scalac-core" % V.scalameta cross CrossVersion.full
    ),
    buildInfoPackage := "scala.meta.internal.metals",
    buildInfoKeys := Seq[BuildInfoKey](
      "localSnapshotVersion" -> localSnapshotVersion,
      "metalsVersion" -> version.value,
      "bspVersion" -> V.bsp,
      "bloopVersion" -> V.bloop,
      "sbtBloopVersion" -> V.sbtBloop,
      "gradleBloopVersion" -> V.gradleBloop,
      "scalametaVersion" -> V.scalameta,
      "semanticdbVersion" -> V.semanticdb,
      "scalafmtVersion" -> V.scalafmt,
      "supportedScalaVersions" -> V.supportedScalaVersions,
      "deprecatedScalaVersions" -> V.deprecatedScalaVersions,
      "scala211" -> V.scala211,
      "scala212" -> V.scala212
    )
  )
  .dependsOn(mtags)
  .enablePlugins(BuildInfoPlugin)

lazy val `sbt-metals` = project
  .settings(
    sbtPlugin := true,
    crossScalaVersions := List(V.scala212, V.scala210),
    sbtVersion in pluginCrossBuild := {
      scalaBinaryVersion.value match {
        case "2.10" => "0.13.17"
        case "2.12" => "1.0.4"
      }
    },
    libraryDependencies --= libraryDependencies.in(ThisBuild).value,
    scalacOptions --= Seq("-Yrangepos", "-Ywarn-unused-import"),
    buildInfoPackage := "scala.meta.internal.sbtmetals",
    buildInfoKeys := Seq[BuildInfoKey](
      "metalsVersion" -> version.value,
      "supportedScalaVersions" -> V.supportedScalaVersions,
      "scalametaVersion" -> V.scalameta
    )
  )
  .enablePlugins(BuildInfoPlugin)
  .disablePlugins(ScalafixPlugin)

lazy val input = project
  .in(file("tests/input"))
  .settings(
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
      "org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.full
    )
  )

lazy val testSettings: Seq[Def.Setting[_]] = List(
  skip.in(publish) := true,
  fork := true,
  testFrameworks := List(new TestFramework("utest.runner.Framework"))
)

lazy val mtest = project
  .in(file("tests/mtest"))
  .settings(
    skip.in(publish) := true,
    crossScalaVersions := List(V.scala212, V.scala211),
    libraryDependencies ++= List(
      "com.geirsson" %% "coursier-small" % "1.3.3",
      "org.scalameta" %% "testkit" % V.scalameta,
      "com.lihaoyi" %% "utest" % "0.6.0"
    ),
    buildInfoPackage := "tests",
    buildInfoObject := "BuildInfoVersions",
    buildInfoKeys := Seq[BuildInfoKey](
      "scala211" -> V.scala211,
      "scala212" -> V.scala212
    )
  )
  .dependsOn(mtags)
  .enablePlugins(BuildInfoPlugin)

lazy val cross = project
  .in(file("tests/cross"))
  .settings(
    testSettings,
    libraryDependencies ++= List(
      "com.chuusai" %% "shapeless" % "2.3.3",
      "org.typelevel" %% "cats-core" % "1.6.0",
      "com.github.mpilquist" %% "simulacrum" % "0.15.0",
      "com.olegpy" %% "better-monadic-for" % "0.3.0-M4",
      "org.spire-math" %% "kind-projector" % "0.9.8",
      "org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.full
    ),
    crossScalaVersions := V.supportedScalaVersions
  )
  .dependsOn(mtest, mtags)

lazy val unit = project
  .in(file("tests/unit"))
  .settings(
    testSettings,
    libraryDependencies ++= List(
      "io.get-coursier" %% "coursier" % coursier.util.Properties.version, // for jars
      "io.get-coursier" %% "coursier-cache" % coursier.util.Properties.version,
      "org.scalameta" % "metac" % V.scalameta cross CrossVersion.full,
      "org.scalameta" %% "testkit" % V.scalameta,
      "ch.epfl.scala" %% "bloop-config" % V.bloop,
      "com.lihaoyi" %% "utest" % "0.6.0"
    ),
    buildInfoPackage := "tests",
    resourceGenerators.in(Compile) += InputProperties.resourceGenerator(input),
    compile.in(Compile) :=
      compile.in(Compile).dependsOn(compile.in(input, Test)).value,
    buildInfoKeys := Seq[BuildInfoKey](
      "sourceroot" -> baseDirectory.in(ThisBuild).value,
      "targetDirectory" -> target.in(Test).value,
      "testResourceDirectory" -> resourceDirectory.in(Test).value
    )
  )
  .dependsOn(mtest, metals)
  .enablePlugins(BuildInfoPlugin)

val cross211publishLocal = Def.task[Unit] {
  // Runs `publishLocal` for mtags with 2.11 scalaVersion.
  val newState = Project
    .extract(state.value)
    .appendWithSession(
      List(
        scalaVersion.in(mtags) := V.scala211
      ),
      state.value
    )
  Project
    .extract(newState)
    .runTask(publishLocal.in(mtags), newState)
}
lazy val slow = project
  .in(file("tests/slow"))
  .settings(
    testSettings,
    testOnly.in(Test) := testOnly
      .in(Test)
      .dependsOn(
        publishLocal.in(`sbt-metals`),
        cross211publishLocal
      )
      .evaluated,
    test.in(Test) := test
      .in(Test)
      .dependsOn(
        publishLocal.in(`sbt-metals`),
        cross211publishLocal
      )
      .value
  )
  .dependsOn(unit)

lazy val bench = project
  .in(file("metals-bench"))
  .settings(
    fork.in(run) := true,
    skip.in(publish) := true,
    moduleName := "metals-bench",
    libraryDependencies ++= List(
      // for measuring memory usage
      "org.spire-math" %% "clouseau" % "0.2.2"
    )
  )
  .dependsOn(unit)
  .enablePlugins(JmhPlugin)

lazy val docs = project
  .in(file("metals-docs"))
  .settings(
    skip.in(publish) := true,
    moduleName := "metals-docs",
    mdoc := run.in(Compile).evaluated,
    libraryDependencies ++= List(
      "org.jsoup" % "jsoup" % "1.11.3"
    )
  )
  .dependsOn(metals)
  .enablePlugins(DocusaurusPlugin)
