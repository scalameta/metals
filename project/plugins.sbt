addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.4.0")
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.9.24")
addSbtPlugin("com.geirsson" % "sbt-ci-release" % "1.5.5")
addSbtPlugin("org.scalameta" % "sbt-mdoc" % "2.2.13")
addSbtPlugin("org.scalameta" % "sbt-munit" % "0.7.19")
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.10.0")
addSbtPlugin("ch.epfl.lamp" % "sbt-dotty" % "0.4.6")
addSbtPlugin("com.github.reibitto" % "sbt-welcome" % "0.2.0")

libraryDependencies += "org.scala-sbt" %% "scripted-plugin" % sbtVersion.value

resolvers += Resolver.sonatypeRepo("public")
