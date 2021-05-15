addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.4.2")
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.9.27")
addSbtPlugin("com.geirsson" % "sbt-ci-release" % "1.5.7")
addSbtPlugin("org.scalameta" % "sbt-mdoc" % "2.2.21")
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.10.0")
addSbtPlugin("com.github.reibitto" % "sbt-welcome" % "0.2.1")

libraryDependencies += "org.scala-sbt" %% "scripted-plugin" % sbtVersion.value

resolvers += Resolver.sonatypeRepo("public")
