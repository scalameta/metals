import java.io.File
inThisBuild(
  List(
    version ~= { dynVer =>
      if (sys.env.contains("CI")) dynVer
      else "0.2.0-SNAPSHOT" // only for local publishng
    },
    scalaVersion := V.scala212,
    crossScalaVersions := List(V.scala212),
    scalacOptions ++= List(
      "-Yrangepos",
      "-deprecation",
      // -Xlint is unusable because of
      // https://github.com/scala/bug/issues/10448
      "-Ywarn-unused-import"
    ),
    addCompilerPlugin(
      "org.scalameta" % "semanticdb-scalac" % "4.0.0" cross CrossVersion.full
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
        "jvican",
        "Jorge Vicente Cantero",
        "jorgevc@fastmail.es",
        url("https://jvican.github.io/")
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
    }
  )
)

cancelable.in(Global) := true

addCommandAlias("scalafixAll", "all compile:scalafix test:scalafix")
addCommandAlias("scalafixCheck", "; scalafix --check ; test:scalafix --check")

commands += Command.command("save-expect") { s =>
  "unit/test:runMain tests.SaveExpect" ::
    s
}

lazy val V = new {
  val scala210 = "2.10.7"
  val scala211 = "2.11.12"
  val scala212 = "2.12.7"
  val scalameta = "4.0.0"
  val semanticdb = "4.0.0-163560a8"
  val bsp = "2.0.0-M1"
  val sbtBloop = "121807cc"
  val bloop = "1.0.0+369-a2222610"
}

skip.in(publish) := true

lazy val mtags = project
  .settings(
    libraryDependencies ++= List(
      "com.thoughtworks.qdox" % "qdox" % "2.0-M9", // for java mtags
      "org.scalameta" %% "scalameta" % V.scalameta
    )
  )

lazy val metals = project
  .settings(
    fork.in(Compile, run) := true,
    resolvers += Resolver.bintrayRepo("scalacenter", "releases"),
    // As a general rule of thumb, we try to keep Scala dependencies to a minimum.
    libraryDependencies ++= List(
      // =================
      // Java dependencies
      // =================
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
      // for fetching ch.epfl.scala:bloop-frontend and other library dependencies
      "com.geirsson" %% "coursier-small" % "1.2.0+3-f1fa68e5-SNAPSHOT",
      // undeclared transitive dependency of coursier-small
      "org.scala-lang.modules" %% "scala-xml" % "1.1.1",
      // for handling Java futures
      "org.scala-lang.modules" %% "scala-java8-compat" % "0.9.0",
      // for logging
      "com.outr" %% "scribe" % "2.6.0",
      "com.outr" %% "scribe-slf4j" % "2.6.0", // needed for flyway database migrations
      // for debugging purposes, not strictly needed but nice for productivity
      "com.lihaoyi" %% "pprint" % "0.5.3",
      // for producing SemanticDB from Scala source files
      "org.scalameta" %% "scalameta" % V.scalameta,
      "org.scalameta" % "interactive" % V.scalameta cross CrossVersion.full
    ),
    buildInfoPackage := "scala.meta.internal.metals",
    buildInfoKeys := Seq[BuildInfoKey](
      "metalsVersion" -> version.value,
      "bspVersion" -> V.bsp,
      "bloopVersion" -> V.bloop,
      "sbtBloopVersion" -> V.sbtBloop,
      "scalametaVersion" -> V.scalameta,
      "semanticdbVersion" -> V.semanticdb,
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
    addSbtPlugin("ch.epfl.scala" % "sbt-bloop" % V.sbtBloop),
    sbtVersion in pluginCrossBuild := {
      scalaBinaryVersion.value match {
        case "2.10" => "0.13.17"
        case "2.12" => "1.0.4"
      }
    },
    publishMavenStyle := false,
    libraryDependencies --= libraryDependencies.in(ThisBuild).value,
    scalacOptions --= Seq("-Yrangepos", "-Ywarn-unused-import")
  )
  .disablePlugins(ScalafixPlugin)

lazy val input = project
  .in(file("tests/input"))
  .settings(
    skip.in(publish) := true,
    libraryDependencies ++= List(
      // these projects have macro annotations
      "org.scalameta" %% "scalameta" % V.scalameta,
      "io.circe" %% "circe-derivation-annotations" % "0.9.0-M5"
    ),
    addCompilerPlugin(
      "org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.full
    )
  )

lazy val testSettings: Seq[Def.Setting[_]] = List(
  skip.in(publish) := true,
  fork := true,
  testFrameworks := List(new TestFramework("utest.runner.Framework"))
)

lazy val unit = project
  .in(file("tests/unit"))
  .settings(
    testSettings,
    libraryDependencies ++= List(
      "io.get-coursier" %% "coursier" % coursier.util.Properties.version, // for jars
      "io.get-coursier" %% "coursier-cache" % coursier.util.Properties.version,
      "org.scalameta" %% "symtab" % V.scalameta,
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
      "testResourceDirectory" -> resourceDirectory.in(Test).value
    )
  )
  .dependsOn(metals)
  .enablePlugins(BuildInfoPlugin)
lazy val slow = project
  .in(file("tests/slow"))
  .settings(
    testSettings
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
      "org.spire-math" %% "clouseau" % "0.2.2",
      "org.openjdk.jol" % "jol-core" % "0.9"
    )
  )
  .dependsOn(unit)
  .enablePlugins(JmhPlugin)

lazy val docs = project
  .in(file("metals-docs"))
  .settings(
    skip.in(publish) := true,
    moduleName := "metals-docs",
    mainClass.in(Compile) := Some("docs.Docs"),
    libraryDependencies ++= List(
      "com.geirsson" % "mdoc" % "0.6.0" cross CrossVersion.full
    )
  )
  .dependsOn(metals)
  .enablePlugins(DocusaurusPlugin)
