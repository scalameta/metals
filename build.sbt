scalaVersion in ThisBuild := "2.12.3"

lazy val `language-server` = project
  .settings(
    name := "scalameta-language-server",
    organization := "org.scalameta",
    version := "0.1-SNAPSHOT",
    resolvers += "dhpcs at bintray" at "https://dl.bintray.com/dhpcs/maven",
    libraryDependencies ++= List(
      "ch.epfl.scala" % "scalafix-cli" % "0.5.3" cross CrossVersion.full,
      "com.github.dragos" %% "languageserver" % "0.2.1-SNAPSHOT",
      "com.geirsson" %% "scalafmt-core" % "1.3.0"
    )
  )
