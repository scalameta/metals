inThisBuild(
  List(
    version ~= { dynVer =>
      if (sys.env.contains("CI")) dynVer
      else "SNAPSHOT" // only for local publishng
    },
    scalacOptions ++= List(
      "-Yrangepos",
      "-deprecation",
      // -Xlint is unusable because of
      // https://github.com/scala/bug/issues/10448
      "-Ywarn-unused-import"
    ),
    organization := "org.scalameta",
    licenses := Seq(
      "Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")
    ),
    testFrameworks := List(new TestFramework("utest.runner.Framework")),
    libraryDependencies += "com.lihaoyi" %% "utest" % "0.6.0" % Test,
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
    resolvers += Resolver.sonatypeRepo("releases"),
    // faster publishLocal:
    publishArtifact in packageDoc := sys.env.contains("CI"),
    publishArtifact in packageSrc := sys.env.contains("CI"),
    addCompilerPlugin(
      "org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.full
    )
  )
)

lazy val V = new {
  val scala210 = "2.10.7"
  val scala212 = MetalsPlugin.scala212
  val scalameta = MetalsPlugin.semanticdbVersion
  val scalafix = "0.5.7"
  val circe = "0.9.0"
  val enumeratum = "1.5.12"
}

lazy val legacyScala212 = List(
  addCompilerPlugin(MetalsPlugin.semanticdbScalac),
  scalaVersion := V.scala212,
  crossScalaVersions := List(V.scala212),
)

skip in publish := true
legacyScala212

lazy val metals = project
  .enablePlugins(BuildInfoPlugin)
  .settings(
    legacyScala212,
    fork in Test := true, // required for jni interrop with leveldb.
    buildInfoKeys := Seq[BuildInfoKey](
      "testWorkspaceBaseDirectory" ->
        baseDirectory.in(testWorkspace).value,
      version,
    ),
    buildInfoPackage := "scala.meta.metals.internal",
    libraryDependencies ++= List(
      "com.lihaoyi" %% "pprint" % "0.5.3", // for pretty formatting of log values
      "org.scala-sbt.ipcsocket" % "ipcsocket" % "1.0.0", // for sbt server
      "ch.epfl.scala" % "scalafix-reflect" % V.scalafix cross CrossVersion.full,
      "com.googlecode.java-diff-utils" % "diffutils" % "1.3.0", // for edit-distance
      "com.thoughtworks.qdox" % "qdox" % "2.0-M7", // for java mtags
      "io.get-coursier" %% "coursier" % coursier.util.Properties.version, // for jars
      "io.get-coursier" %% "coursier-cache" % coursier.util.Properties.version,
      "io.github.soc" % "directories" % "5", // for cache location
      "me.xdrop" % "fuzzywuzzy" % "1.1.9", // for workspace/symbol
      "org.fusesource.leveldbjni" % "leveldbjni-all" % "1.8", // for caching classpath index
      "org.scalameta" %% "lsp4s" % "0.2.1",
      "org.scalameta" %% "semanticdb-scalac" % V.scalameta cross CrossVersion.full,
      "io.circe" %% "circe-core" % V.circe,
      "io.circe" %% "circe-generic" % V.circe,
      "io.circe" %% "circe-generic-extras" % V.circe,
      "io.circe" %% "circe-parser" % V.circe,
      "com.beachape" %% "enumeratum" % V.enumeratum,
      "com.beachape" %% "enumeratum-circe" % "1.5.15",
      "org.scalameta" %% "testkit" % V.scalameta % Test,
    )
  )
  .dependsOn(
    testWorkspace % "test->test"
  )

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

lazy val integration = project
  .in(file("tests/integration"))
  .settings(
    legacyScala212,
    skip in publish := true
  )
  .dependsOn(metals % "compile->compile;test->test")

lazy val testWorkspace = project
  .in(file("test-workspace"))
  .settings(
    legacyScala212,
    skip in publish := true,
    scalacOptions += {
      // Need to fix source root so it matches the workspace folder.
      s"-P:semanticdb:sourceroot:${baseDirectory.value}"
    },
    scalacOptions += "-Ywarn-unused-import",
    scalacOptions -= "-Xlint"
  )

lazy val docs = project
  .in(file("metals-docs"))
  .settings(
    skip in publish := true,
    sources.in(Compile) += {
      sourceDirectory.in(metals, Compile).value /
        "scala/scala/meta/metals/Configuration.scala"
    },
    scalaVersion := "2.12.6",
    crossScalaVersions := List("2.12.6"),
    mainClass.in(Compile) := Some("docs.Docs"),
    SettingKey[Boolean]("metalsEnabled") := false,
    libraryDependencies ++= List(
      "com.geirsson" % "mdoc" % "0.4.5" cross CrossVersion.full,
      // Dependencies below can be removed after the upgrade to Scalameta v4.0
      "io.circe" %% "circe-core" % V.circe,
      "io.circe" %% "circe-generic" % V.circe,
      "io.circe" %% "circe-generic-extras" % V.circe,
      "io.circe" %% "circe-parser" % V.circe,
      "com.beachape" %% "enumeratum" % V.enumeratum,
      "com.beachape" %% "enumeratum-circe" % "1.5.15"
    ),
    buildInfoKeys := Seq[BuildInfoKey](
      version,
    ),
    buildInfoPackage := "docs"
  )
  .enablePlugins(DocusaurusPlugin, BuildInfoPlugin)
