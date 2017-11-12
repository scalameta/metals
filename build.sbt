inThisBuild(
  List(
    scalaVersion := "2.12.3",
    organization := "org.scalameta",
    version := "0.1-SNAPSHOT"
  )
)

lazy val metaserver = project
  .settings(
    resolvers += "dhpcs at bintray" at "https://dl.bintray.com/dhpcs/maven",
    libraryDependencies ++= List(
      "io.monix" %% "monix" % "2.3.0",
      "io.get-coursier" %% "coursier" % coursier.util.Properties.version,
      "io.get-coursier" %% "coursier-cache" % coursier.util.Properties.version,
      "ch.epfl.scala" % "scalafix-cli" % "0.5.3" cross CrossVersion.full,
      "com.geirsson" %% "scalafmt-core" % "1.3.0",
      "org.eclipse.lsp4j" % "org.eclipse.lsp4j" % "0.3.0",
      "com.typesafe.scala-logging" %% "scala-logging" % "3.7.2",
      "org.slf4j" % "slf4j-api" % "1.7.21",
      "ch.qos.logback" % "logback-classic" % "1.1.7"
    )
  )
