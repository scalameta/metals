import java.io.File
inThisBuild(
  List(
    version ~= { dynVer =>
      if (sys.env.contains("CI")) dynVer
      else "SNAPSHOT" // only for local publishng
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
    addCompilerPlugin(MetalsPlugin.semanticdbScalac),
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
    publishArtifact in packageDoc := sys.env.contains("CI"),
    publishArtifact in packageSrc := sys.env.contains("CI")
  )
)

commands += Command.command("save-expect") { s =>
  "unit/test:runMain tests.SaveExpect" ::
    s
}

lazy val V = new {
  val scala210 = "2.10.7"
  val scala212 = MetalsPlugin.scala212
  val scalameta = MetalsPlugin.semanticdbVersion
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
    libraryDependencies ++= List(
      "com.thoughtworks.qdox" % "qdox" % "2.0-M9", // for java mtags
      "com.lihaoyi" %% "pprint" % "0.5.3", // for pretty formatting of log values
      "org.scalameta" %% "scalameta" % V.scalameta,
      "org.scalameta" %% "symtab" % V.scalameta,
      "org.scalameta" % "interactive" % V.scalameta cross CrossVersion.full,
      "org.scalameta" %% "lsp4s" % "0.2.1"
    )
  )
  .dependsOn(mtags)

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
    publishMavenStyle := false,
    libraryDependencies --= libraryDependencies.in(ThisBuild).value,
    scalacOptions --= Seq("-Yrangepos", "-Ywarn-unused-import"),
    scriptedBufferLog := !sys.env.contains("CI"),
    scriptedLaunchOpts ++= Seq(
      "-Xmx1024M",
      s"-Dplugin.version=${version.value}",
    ),
  )
  .enablePlugins(ScriptedPlugin)

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

lazy val unit = project
  .in(file("tests/unit"))
  .settings(
    skip.in(publish) := true,
    testFrameworks := List(new TestFramework("utest.runner.Framework")),
    libraryDependencies ++= List(
      "io.get-coursier" %% "coursier" % coursier.util.Properties.version, // for jars
      "io.get-coursier" %% "coursier-cache" % coursier.util.Properties.version,
      "org.scalameta" % "metac" % V.scalameta cross CrossVersion.full,
      "org.scalameta" %% "testkit" % V.scalameta,
      "com.lihaoyi" %% "utest" % "0.6.0",
    ),
    buildInfoPackage := "tests",
    resourceGenerators.in(Compile) += InputProperties.resourceGenerator(input),
    compile.in(Compile) :=
      compile.in(Compile).dependsOn(compile.in(input, Test)).value,
    buildInfoKeys := Seq[BuildInfoKey](
      "testResourceDirectory" -> resourceDirectory.in(Test).value
    )
  )
  .dependsOn(metals)
  .enablePlugins(BuildInfoPlugin)

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
    ),
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
      "com.geirsson" % "mdoc" % "0.5.1" cross CrossVersion.full
    ),
    buildInfoKeys := Seq[BuildInfoKey](
      version,
    ),
    buildInfoPackage := "docs"
  )
  .dependsOn(metals)
  .enablePlugins(DocusaurusPlugin, BuildInfoPlugin)
