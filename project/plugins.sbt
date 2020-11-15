addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.4.0")
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.9.23")
addSbtPlugin("com.geirsson" % "sbt-ci-release" % "1.5.3")
addSbtPlugin("org.scalameta" % "sbt-mdoc" % "2.2.10")
addSbtPlugin("org.scalameta" % "sbt-munit" % "0.7.17")

addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.10.0")
addSbtPlugin("ch.epfl.lamp" % "sbt-dotty" % "0.4.5")

libraryDependencies += "org.scala-sbt" %% "scripted-plugin" % sbtVersion.value

resolvers += Resolver.sonatypeRepo("public")
