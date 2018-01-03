inThisBuild(
//  semanticdbSettings ++
  List(
    version ~= { old =>
      if (sys.env.contains("CI")) old
      else "0.1-SNAPSHOT" // to avoid manually updating extension.js
    },
    scalaVersion := V.scala212,
    scalacOptions ++= List(
      "-deprecation",
//      "-Xlint"
    ),
    scalafixEnabled := false,
    organization := "org.scalameta",
    licenses := Seq(
      "Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")
    ),
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
    publishArtifact in packageSrc := sys.env.contains("CI")
  )
)

lazy val V = new {
  val scala212 = "2.12.4"
  val scalameta = "2.1.5"
  val scalafix = "0.5.7"
  val enumeratum = "1.5.12"
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

lazy val languageserver = project
  .settings(
    resolvers += Resolver.bintrayRepo("dhpcs", "maven"),
    libraryDependencies ++= Seq(
      "com.dhpcs" %% "scala-json-rpc" % "2.0.1",
      "com.beachape" %% "enumeratum" % V.enumeratum,
      "com.beachape" %% "enumeratum-play-json" % "1.5.12-2.6.0-M7",
      "com.typesafe.scala-logging" %% "scala-logging" % "3.7.2",
      "io.monix" %% "monix" % "2.3.0",
      "org.slf4j" % "slf4j-api" % "1.7.25",
      "ch.qos.logback" % "logback-classic" % "1.2.3",
      "org.codehaus.groovy" % "groovy" % "2.4.0",
      "org.scalatest" %% "scalatest" % "3.0.1" % Test
    )
  )

lazy val metaserver = project
  .settings(
    PB.targets.in(Compile) := Seq(
      scalapb.gen(
        flatPackage = true // Don't append filename to package
      ) -> sourceManaged.in(Compile).value./("protobuf")
    ),
    resolvers += Resolver.bintrayRepo("dhpcs", "maven"),
    testFrameworks := new TestFramework("utest.runner.Framework") :: Nil,
    fork in Test := true, // required for jni interrop with leveldb.
    buildInfoKeys := Seq[BuildInfoKey](
      "testWorkspaceBaseDirectory" ->
        baseDirectory.in(testWorkspace).value
    ),
    buildInfoPackage := "scala.meta.languageserver.internal",
    libraryDependencies ++= List(
      "com.googlecode.java-diff-utils" % "diffutils" % "1.3.0",
      "io.github.soc" % "directories" % "5",
      "org.fusesource.leveldbjni" % "leveldbjni-all" % "1.8",
      "me.xdrop" % "fuzzywuzzy" % "1.1.9",
      "com.lihaoyi" %% "pprint" % "0.5.3",
      "com.thoughtworks.qdox" % "qdox" % "2.0-M7", // for java mtags
      "io.get-coursier" %% "coursier" % coursier.util.Properties.version,
      "io.get-coursier" %% "coursier-cache" % coursier.util.Properties.version,
      "ch.epfl.scala" % "scalafix-cli" % V.scalafix cross CrossVersion.full,
      "org.scalameta" %% "semanticdb-scalac" % V.scalameta cross CrossVersion.full,
      "com.beachape" %% "enumeratum" % V.enumeratum,
      "com.lihaoyi" %% "utest" % "0.6.0" % Test,
      "org.scalameta" %% "testkit" % V.scalameta % Test
    )
  )
  .dependsOn(
    languageserver,
    testWorkspace % "test->test"
  )
  .enablePlugins(BuildInfoPlugin)

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
