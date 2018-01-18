inThisBuild(
  semanticdbSettings ++
    List(
      version ~= { old =>
        if (sys.env.contains("CI")) old
        else "0.1-SNAPSHOT" // to avoid manually updating extension.js
      },
      scalaVersion := V.scala212,
      scalacOptions ++= List(
        "-deprecation",
        // -Xlint is unusable because of
        // https://github.com/scala/bug/issues/10448
        "-Ywarn-unused:imports"
      ),
      scalafixEnabled := false,
      organization := "org.scalameta",
      licenses := Seq(
        "Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")
      ),
      testFrameworks := new TestFramework("utest.runner.Framework") :: Nil,
      libraryDependencies += "com.lihaoyi" %% "utest" % "0.6.0" % Test,
      homepage := Some(url("https://github.com/scalameta/language-server")),
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
          url("https://github.com/scalameta/language-server"),
          s"scm:git:git@github.com:scalameta/language-server.git"
        )
      ),
      releaseEarlyWith := BintrayPublisher,
      releaseEarlyEnableSyncToMaven := false,
      publishMavenStyle := true,
      bintrayOrganization := Some("scalameta"),
      bintrayReleaseOnPublish := dynverGitDescribeOutput.value.isVersionStable,
      // faster publishLocal:
      publishArtifact in packageDoc := sys.env.contains("CI"),
      publishArtifact in packageSrc := sys.env.contains("CI"),
      addCompilerPlugin(
        "org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.full
      )
    )
)

lazy val V = new {
  val scala212 = "2.12.4"
  val scalameta = "2.1.5"
  val scalafix = "0.5.7"
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

// not publishing the root project
noPublish

lazy val semanticdbSettings = List(
  addCompilerPlugin(
    "org.scalameta" % "semanticdb-scalac" % V.scalameta cross CrossVersion.full
  ),
  scalacOptions += "-Yrangepos"
)

lazy val jsonrpc = project
  .settings(
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

lazy val lsp4s = project.dependsOn(jsonrpc)

lazy val metaserver = project
  .settings(
    PB.targets.in(Compile) := Seq(
      scalapb.gen(
        flatPackage = true // Don't append filename to package
      ) -> sourceManaged.in(Compile).value./("protobuf")
    ),
    fork in Test := true, // required for jni interrop with leveldb.
    buildInfoKeys := Seq[BuildInfoKey](
      "testWorkspaceBaseDirectory" ->
        baseDirectory.in(testWorkspace).value
    ),
    buildInfoPackage := "scala.meta.languageserver.internal",
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
      "org.scalameta" %% "semanticdb-scalac" % V.scalameta cross CrossVersion.full,
      "org.scalameta" %% "testkit" % V.scalameta % Test
    )
  )
  .dependsOn(
    testWorkspace % "test->test",
    lsp4s
  )
  .enablePlugins(BuildInfoPlugin)

lazy val integration = project
  .in(file("tests/integration"))
  .settings(
    noPublish
  )
  .dependsOn(metaserver % "compile->compile;test->test")

lazy val testWorkspace = project
  .in(file("test-workspace"))
  .settings(
    noPublish,
    scalacOptions += {
      // Need to fix source root so it matches the workspace folder.
      s"-P:semanticdb:sourceroot:${baseDirectory.value}"
    },
    scalacOptions += "-Ywarn-unused-import",
    scalacOptions -= "-Xlint"
  )
  .disablePlugins(ScalafixPlugin)
