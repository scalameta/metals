inThisBuild(
  List(
    scalaVersion := "2.12.4",
    addCompilerPlugin(
      "org.scalameta" % "semanticdb-scalac" % "2.1.1" cross CrossVersion.full
    ),
    libraryDependencies ++= List(
      "com.vladsch.flexmark" % "flexmark-all" % "0.26.4",
      ("org.scalatest" %% "scalatest" % "3.0.3" % Test).withSources()
    ),
    scalacOptions += "-Yrangepos"
  )
)
lazy val a = project
lazy val b = project.dependsOn(a)
