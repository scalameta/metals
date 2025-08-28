package tests.sbt

import scala.meta.internal.metals.{BuildInfo => V}

import tests.CompletionsAssertions
import tests.ScriptsAssertions

class SbtTwirlSuite
    extends SbtServerSuite
    with CompletionsAssertions
    with ScriptsAssertions {

  val twirlVersion = "2.0.9"

  test("twirl-hover") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/project/build.properties
            |sbt.version=${V.sbtVersion}
            |/src/main/twirl/example.scala.html
            |@(name: String)
            |<h1>Hello @name!</h1>
            |/project/plugins.sbt
            |addSbtPlugin("org.playframework.twirl" % "sbt-twirl" % "${twirlVersion}")
            |/build.sbt
            |enablePlugins(SbtTwirl)
            |Compile / unmanagedSourceDirectories := Seq(
            |  (baseDirectory.value / "src" / "main" / "scala"),
            |  (baseDirectory.value / "src" / "main" / "scala-3"),
            |  (baseDirectory.value / "src" / "main" / "java"),
            |  (baseDirectory.value / "src" / "main" / "twirl")
            |)
            |scalaVersion := "${V.scala213}"
            |""".stripMargin
      )
      _ <- server.didOpen("src/main/twirl/example.scala.html")
      _ <- server.didOpen("build.sbt")
      _ <- server.assertHover(
        "src/main/twirl/example.scala.html",
        """|@(name: String)
           |<h1>Hello @na@@me!</h1>
           |""".stripMargin,
        """|```scala
           |name: String
           |```""".stripMargin,
      )
    } yield ()
  }

  test("hover-method") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/project/build.properties
            |sbt.version=${V.sbtVersion}
            |/src/main/twirl/example.scala.html
            |@(name: String)
            |<h1>Hello @name!</h1>
            |/project/plugins.sbt
            |addSbtPlugin("org.playframework.twirl" % "sbt-twirl" % "${twirlVersion}")
            |/build.sbt
            |enablePlugins(SbtTwirl)
            |Compile / unmanagedSourceDirectories := Seq(
            |  (baseDirectory.value / "src" / "main" / "scala"),
            |  (baseDirectory.value / "src" / "main" / "scala-3"),
            |  (baseDirectory.value / "src" / "main" / "java"),
            |  (baseDirectory.value / "src" / "main" / "twirl")
            |)
            |scalaVersion := "${V.scala213}"
            |""".stripMargin
      )
      _ <- server.didOpen("src/main/twirl/example.scala.html")
      _ <- server.didOpen("build.sbt")
      _ <- server.assertHover(
        "src/main/twirl/example.scala.html",
        """|@(x: String, y: Int)
           |<p>@x.len@@gth @y</p>
           |""".stripMargin,
        """|```scala
           |def length(): Int
           |```""".stripMargin,
      )
    } yield ()
  }

  test("twirl-completion") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|src/main/twirl/example.scala.html
            |@(name: String)
            |<h1>Hello @// @@</h1>
            |/project/plugins.sbt
            |addSbtPlugin("org.playframework.twirl" % "sbt-twirl" % "${twirlVersion}")
            |/build.sbt
            |enablePlugins(SbtTwirl)
            |Compile / unmanagedSourceDirectories := Seq(
            |  (baseDirectory.value / "src" / "main" / "scala"),
            |  (baseDirectory.value / "src" / "main" / "scala-3"),
            |  (baseDirectory.value / "src" / "main" / "java"),
            |  (baseDirectory.value / "src" / "main" / "twirl")
            |)
            |""".stripMargin
      )
      _ <- server.didOpen("src/main/twirl/example.scala.html")
      _ = assertNoDiagnostics()
      _ <- assertCompletion(
        "name.@@",
        "toInt: Int\ntoIntOption: Option[Int]\n",
        filename = Some("src/main/twirl/example.scala.html"),
        filter = _.contains("toInt"),
      )
    } yield ()
  }

  test("twirl-definition") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|src/main/twirl/example.scala.html
            |@(name: String)
            |<h1>Hello @name.toInt</h1>
            |/project/plugins.sbt
            |addSbtPlugin("org.playframework.twirl" % "sbt-twirl" % "${twirlVersion}")
            |/build.sbt
            |enablePlugins(SbtTwirl)
            |Compile / unmanagedSourceDirectories := Seq(
            |  (baseDirectory.value / "src" / "main" / "scala"),
            |  (baseDirectory.value / "src" / "main" / "scala-3"),
            |  (baseDirectory.value / "src" / "main" / "java"),
            |  (baseDirectory.value / "src" / "main" / "twirl")
            |)
            |""".stripMargin
      )
      _ <- server.didOpen("src/main/twirl/example.scala.html")

      res <- definitionsAt(
        "src/main/twirl/example.scala.html",
        """|@(name: String)
           |<h1>Hello @name.toI@@nt</h1>
           |""".stripMargin,
      )
      _ = assert(
        res.head
          .getUri()
          .toString
          .contains("StringLike.scala")
      )
    } yield ()
  }
}
