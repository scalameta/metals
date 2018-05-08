addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.3.2")
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.5.7")
addSbtPlugin("ch.epfl.scala" % "sbt-release-early" % "2.0.0")
addSbtPlugin("io.get-coursier" % "sbt-coursier" % "1.0.0-RC13")
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.7.0")
addSbtPlugin(
  "com.thesamet" % "sbt-protoc" % "0.99.18" exclude ("com.trueaccord.scalapb", "protoc-bridge_2.12")
)
libraryDependencies += "com.trueaccord.scalapb" %% "compilerplugin-shaded" % "0.6.6"
addSbtPlugin("com.typesafe.sbt" % "sbt-site" % "1.3.2")
addSbtPlugin("org.tpolecat" % "tut-plugin" % "0.6.4")

unmanagedSourceDirectories in Compile +=
  baseDirectory.value.getParentFile / "sbt-metals" / "src" / "main" / "scala"
