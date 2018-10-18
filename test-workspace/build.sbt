bloopExportJarClassifiers in Global := Some(Set("sources"))
addCompilerPlugin(MetalsPlugin.semanticdbScalac)
scalaVersion := "2.12.7"
scalacOptions += "-Yrangepos"
libraryDependencies += "com.lihaoyi" %% "ujson" % "0.6.6"
