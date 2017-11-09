inThisBuild(
  List(
    scalaVersion := "2.12.3",
    addCompilerPlugin(
      "org.scalameta" % "semanticdb-scalac" % "2.0.1" cross CrossVersion.full
    ),
    libraryDependencies +=
      ("org.scalatest" %% "scalatest" % "3.0.3" % Test).withSources(),
    scalacOptions += "-Yrangepos"
  )
)
lazy val a = project
lazy val b = project.dependsOn(a)
