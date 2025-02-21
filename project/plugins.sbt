addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.4.7")
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.2")
addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.9.2")
addSbtPlugin("org.scalameta" % "sbt-mdoc" % "2.5.2")
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.13.1")
addSbtPlugin("com.github.reibitto" % "sbt-welcome" % "0.5.0")

// Mima used for mtags-interfaces
addSbtPlugin("com.typesafe" % "sbt-mima-plugin" % "1.1.4")

libraryDependencies += "org.scala-sbt" %% "scripted-plugin" % sbtVersion.value

resolvers ++= Resolver.sonatypeOssRepos("public")
