addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.3.7")
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.9.11")
addSbtPlugin("com.geirsson" % "sbt-ci-release" % "1.4.31")
addSbtPlugin("org.scalameta" % "sbt-mdoc" % "2.0.3")
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.9.0")

libraryDependencies += "org.scala-sbt" %% "scripted-plugin" % sbtVersion.value

resolvers += Resolver.sonatypeRepo("public")
