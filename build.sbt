inThisBuild(
  List(
    scalaVersion := "2.12.3",
    organization := "org.scalameta",
    version := "0.1-SNAPSHOT",
    sources.in(Compile, doc) := Nil // faster publishLocal.
  )
)

lazy val noPublish = List(
  publishArtifact in (Compile, packageDoc) := false,
  publishArtifact in packageDoc := false,
  sources in (Compile, doc) := Seq.empty,
  publishArtifact := false,
  publish := {}
)

lazy val semanticdbSettings = List(
  addCompilerPlugin(
    "org.scalameta" % "semanticdb-scalac" % "2.1.2" cross CrossVersion.full
  ),
  scalacOptions += "-Yrangepos"
)

lazy val languageserver = project
  .settings(
    resolvers += "dhpcs at bintray" at "https://dl.bintray.com/dhpcs/maven",
    libraryDependencies ++= Seq(
      "com.dhpcs" %% "scala-json-rpc" % "2.0.1",
      "com.typesafe.scala-logging" %% "scala-logging" % "3.7.2",
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
    resolvers += "dhpcs at bintray" at "https://dl.bintray.com/dhpcs/maven",
    testFrameworks := new TestFramework("utest.runner.Framework") :: Nil,
    buildInfoKeys := Seq[BuildInfoKey](
      name,
      version,
      "testSourceDirectory" -> sourceDirectory.in(Test).value,
      scalaVersion,
      sbtVersion
    ),
    compileInputs.in(Test, compile) :=
      compileInputs
        .in(Compile, compile)
        .dependsOn(compile.in(testWorkspace, Test))
        .value,
    buildInfoPackage := "scala.meta.languageserver",
    libraryDependencies ++= List(
      "io.monix" %% "monix" % "2.3.0",
      "com.lihaoyi" %% "pprint" % "0.5.3",
      "com.thoughtworks.qdox" % "qdox" % "2.0-M7", // for java ctags
      "io.get-coursier" %% "coursier" % coursier.util.Properties.version,
      "io.get-coursier" %% "coursier-cache" % coursier.util.Properties.version,
      "ch.epfl.scala" % "scalafix-cli" % "0.5.3" cross CrossVersion.full,
      "com.lihaoyi" %% "utest" % "0.6.0" % Test,
      "org.scalameta" %% "testkit" % "2.0.1" % Test
    )
  )
  .dependsOn(languageserver)
  .enablePlugins(BuildInfoPlugin)

lazy val testWorkspace = project
  .in(file("test-workspace") / "a")
  .settings(
    noPublish,
    semanticdbSettings,
    scalacOptions += {
      // Need to fix source root so it matches the workspace folder.
      val sourceRoot = baseDirectory.value / "test-workspace"
      s"-P:semanticdb:sourceroot:$sourceRoot"
    }
  )
