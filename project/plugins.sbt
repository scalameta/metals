addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.7.0")
addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.3.7")
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.9.14")
addSbtPlugin("com.geirsson" % "sbt-ci-release" % "1.5.2")
addSbtPlugin("org.scalameta" % "sbt-mdoc" % "2.2.0")
addSbtPlugin("org.scalameta" % "sbt-munit" % "0.7.2")
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.9.0")
addSbtPlugin("ch.epfl.lamp" % "sbt-dotty" % "0.3.4")

libraryDependencies += "org.scala-sbt" %% "scripted-plugin" % sbtVersion.value

resolvers += Resolver.sonatypeRepo("public")
