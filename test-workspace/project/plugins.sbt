unmanagedSources.in(Compile) += baseDirectory
  .in(ThisBuild)
  .value
  .getParentFile
  .getParentFile / "sbt-metals/src/main/scala/scala/meta/sbt/MetalsPlugin.scala"

addSbtPlugin("ch.epfl.scala" % "sbt-bloop" % "1.0.0")
