scalaVersion in ThisBuild := "2.12.3"

lazy val LanguageServer = RootProject(
  uri(
    "git://github.com/gabro/dragos-vscode-scala.git#formatting"
  )
)
lazy val `dragos-vscode-scala` =
  ProjectRef(LanguageServer.build, "languageserver")

lazy val `language-server` = project
  .settings(
    organization := "org.scalameta",
    version := "0.1-SNAPSHOT",
    resolvers += "dhpcs at bintray" at "https://dl.bintray.com/dhpcs/maven",
    libraryDependencies ++= List(
      "io.monix" %% "monix" % "2.3.0",
      "io.get-coursier" %% "coursier" % coursier.util.Properties.version,
      "io.get-coursier" %% "coursier-cache" % coursier.util.Properties.version,
      "ch.epfl.scala" % "scalafix-cli" % "0.5.3" cross CrossVersion.full,
      "com.geirsson" %% "scalafmt-core" % "1.3.0"
    )
  )
  .dependsOn(`dragos-vscode-scala`)
  .aggregate(`dragos-vscode-scala`)
