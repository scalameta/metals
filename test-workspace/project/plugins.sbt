unmanagedSources.in(Compile) += baseDirectory
  .in(ThisBuild)
  .value
  .getParentFile
  .getParentFile / "project" / "MetalsPlugin.scala"
