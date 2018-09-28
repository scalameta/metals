addSbtPlugin("com.geirsson" % "sbt-ci-release" % "1.2.2")
addSbtPlugin("com.geirsson" % "sbt-docusaurus" % "0.3.2")
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.7.0")

addSbtCoursier

libraryDependencies += "org.scala-sbt" %% "scripted-plugin" % sbtVersion.value

unmanagedSourceDirectories in Compile +=
  baseDirectory.value.getParentFile / "sbt-metals" / "src" / "main" / "scala"
