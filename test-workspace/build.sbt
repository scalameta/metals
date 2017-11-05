lazy val root = project
  .in(file("."))
  .settings(
    scalaVersion := "2.12.3",
    addCompilerPlugin(
      "org.scalameta" % "semanticdb-scalac" % "2.0.1" cross CrossVersion.full
    ),
    scalacOptions += "-Yrangepos"
  )
