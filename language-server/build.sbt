scalaVersion in ThisBuild := "2.12.3"

lazy val root = project.in(file("."))
  .settings(
    name := "scalameta-language-server",
    organization := "org.scalameta",
    version := "0.1-SNAPSHOT",
    resolvers += "dhpcs at bintray" at "https://dl.bintray.com/dhpcs/maven",
    libraryDependencies ++= List(
        "ch.epfl.scala" % "scalafix-cli" % "0.5.3" cross CrossVersion.full,
        "com.github.dragos" %% "languageserver" % "0.2.1"
    )
  )
