bloopExportJarClassifiers in Global := Some(Set("sources"))
addCompilerPlugin(MetalsPlugin.semanticdbScalac)
scalaVersion := "2.12.7"
scalacOptions ++= List(
  "-P:semanticdb:sourceroot:" + baseDirectory.in(ThisBuild).value,
  "-Yrangepos"
)
libraryDependencies += "com.lihaoyi" %% "ujson" % "0.6.6"
// This is a comment
