inThisBuild(
  List(
    version ~= { dynVer =>
      if (sys.env.contains("CI")) dynVer
      else "SNAPSHOT" // only for local publishng
    },
    scalaVersion := V.scala212,
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
    testFrameworks := new TestFramework("utest.runner.Framework") :: Nil,
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
    scmInfo in ThisBuild := Some(
      ScmInfo(
        url("https://github.com/scalameta/metals"),
        s"scm:git:git@github.com:scalameta/metals.git"
      )
    ),
    releaseEarlyWith := BintrayPublisher,
    releaseEarlyEnableSyncToMaven := false,
    publishMavenStyle := true,
    bintrayOrganization := Some("scalameta"),
    bintrayReleaseOnPublish := dynverGitDescribeOutput.value.isVersionStable,
    pgpPublicRing := file("./travis/local.pubring.asc"),
    pgpSecretRing := file("./travis/local.secring.asc"),
    // faster publishLocal:
    publishArtifact in packageDoc := sys.env.contains("CI"),
    publishArtifact in packageSrc := sys.env.contains("CI"),
    addCompilerPlugin(MetalsPlugin.semanticdbScalac),
    addCompilerPlugin(
      "org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.full
    )
  )
)

lazy val V = new {
  val scala211 = MetalsPlugin.scala211
  val scala212 = MetalsPlugin.scala212
  val scalameta = MetalsPlugin.semanticdbVersion
  val scalafix = "0.6.0-M5"
  val enumeratum = "1.5.12"
  val circe = "0.9.0"
  val cats = "1.0.1"
  val monix = "3.0.0-RC3"
}

lazy val noPublish = List(
  publishTo := None,
  publishArtifact := false,
  skip in publish := true
)

lazy val benchmarks = project
  .disablePlugins(ScriptedPlugin)
  .enablePlugins(JmhPlugin)
  .dependsOn(metals)

lazy val jsonrpc = project
  .disablePlugins(ScriptedPlugin)
  .settings(
    crossScalaVersions := List(V.scala211, V.scala212),
    libraryDependencies ++= List(
      "ch.qos.logback" % "logback-classic" % "1.2.3",
      "com.beachape" %% "enumeratum" % V.enumeratum,
      "com.beachape" %% "enumeratum-circe" % "1.5.15",
      "com.lihaoyi" %% "pprint" % "0.5.3",
      "com.typesafe.scala-logging" %% "scala-logging" % "3.7.2",
      "io.circe" %% "circe-core" % V.circe,
      "io.circe" %% "circe-generic" % V.circe,
      "io.circe" %% "circe-generic-extras" % V.circe,
      "io.circe" %% "circe-parser" % V.circe,
      "io.monix" %% "monix" % "2.3.0",
      "org.codehaus.groovy" % "groovy" % "2.4.0",
      "org.slf4j" % "slf4j-api" % "1.7.25",
      "org.typelevel" %% "cats-core" % V.cats
    )
  )

lazy val lsp4s = project
  .disablePlugins(ScriptedPlugin)
  .settings(crossScalaVersions := List(V.scala211, V.scala212))
  .dependsOn(jsonrpc)

lazy val metals = project
  .enablePlugins(BuildInfoPlugin)
  .disablePlugins(ScriptedPlugin)
  .settings(
    fork in Test := true, // required for jni interrop with leveldb.
    buildInfoKeys := Seq[BuildInfoKey](
      "testWorkspaceBaseDirectory" ->
        baseDirectory.in(testWorkspace).value,
      version,
    ),
    buildInfoPackage := "scala.meta.metals.internal",
    libraryDependencies ++= List(
      "org.scala-sbt.ipcsocket" % "ipcsocket" % "1.0.0", // for sbt server
      "ch.epfl.scala" % "scalafix-reflect" % V.scalafix cross CrossVersion.full,
      "com.googlecode.java-diff-utils" % "diffutils" % "1.3.0", // for edit-distance
      "com.thoughtworks.qdox" % "qdox" % "2.0-M7", // for java mtags
      "io.get-coursier" %% "coursier" % coursier.util.Properties.version, // for jars
      "io.get-coursier" %% "coursier-cache" % coursier.util.Properties.version,
      "io.github.soc" % "directories" % "5", // for cache location
      "me.xdrop" % "fuzzywuzzy" % "1.1.9", // for workspace/symbol
      "org.fusesource.leveldbjni" % "leveldbjni-all" % "1.8", // for caching classpath index
      "org.scalameta" % "interactive" % V.scalameta cross CrossVersion.full,
      "org.scalameta" %% "testkit" % V.scalameta % Test
    )
  )
  .dependsOn(
    testWorkspace % "test->test",
    lsp4s
  )

lazy val integration = project
  .in(file("tests/integration"))
  .disablePlugins(ScriptedPlugin)
  .settings(
    noPublish
  )
  .dependsOn(metals % "compile->compile;test->test")

lazy val testWorkspace = project
  .in(file("test-workspace"))
  .disablePlugins(ScriptedPlugin)
  .settings(
    noPublish,
    scalacOptions += {
      // Need to fix source root so it matches the workspace folder.
      s"-P:semanticdb:sourceroot:${baseDirectory.value}"
    },
    scalacOptions += "-Ywarn-unused-import",
    scalacOptions -= "-Xlint"
  )

lazy val metalsRoot = project
  .in(file("."))
  .disablePlugins(ScriptedPlugin)
  .settings(
    noPublish,
    // this is used only by the sbt-metals subproject:
    // we use 1.0 (instead of 1.1) to ensure compatibility with all 1.* versions
    // also the order is important: first 1.+, then 0.13
    crossSbtVersions := Seq("1.0.4", "0.13.17"),
  )
  .aggregate(
    benchmarks,
    jsonrpc,
    lsp4s,
    metals,
    integration
  )

lazy val `sbt-metals` = project
  .enablePlugins(ScriptedPlugin)
  .settings(
    sbtPlugin := true,
    scalaVersion := {
      if (sbtVersion.in(pluginCrossBuild).value.startsWith("0.13")) "2.10.6"
      else Keys.scalaVersion.value
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

commands += Command.command("release") { st =>
  "+releaseEarly" ::
    "^sbt-metals/releaseEarly" ::
    st
}
