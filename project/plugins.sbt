addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.4.8")
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.7")
addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.12.0")
addSbtPlugin("org.scalameta" % "sbt-mdoc" % "2.9.1")
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.13.1")
addSbtPlugin("com.github.reibitto" % "sbt-welcome" % "0.6.0")

// Mima used for mtags-interfaces
addSbtPlugin("com.typesafe" % "sbt-mima-plugin" % "1.1.6")

libraryDependencies += "org.scala-sbt" %% "scripted-plugin" % sbtVersion.value
