addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.3.2")
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.5.7")
addSbtPlugin("ch.epfl.scala" % "sbt-release-early" % "2.0.0")
addSbtPlugin("io.get-coursier" % "sbt-coursier" % "1.0.0-RC13")
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.7.0")
addSbtPlugin(
  "com.thesamet" % "sbt-protoc" % "0.99.12" exclude ("com.trueaccord.scalapb", "protoc-bridge_2.10")
)
libraryDependencies += "com.thesamet.scalapb" %% "compilerplugin-shaded" % "0.7.0-rc7"
