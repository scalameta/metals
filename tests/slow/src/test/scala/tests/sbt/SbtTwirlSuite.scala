package tests.sbt

import scala.meta.internal.metals.{BuildInfo => V}

import tests.CompletionsAssertions

class SbtTwirlSuite extends SbtServerSuite with CompletionsAssertions {

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

  test("twirl-hover-method") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/project/build.properties
            |sbt.version=${V.sbtVersion}
            |/src/main/twirl/example.scala.html
            |@(name: String)
            |<h1>Hello @name.length!</h1>
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
      _ <- server.assertHover(
        "src/main/twirl/example.scala.html",
        """|@(name: String)
           |<h1>Hello @name.len@@gth!</h1>
           |""".stripMargin,
        """|```scala
           |def length(): Int
           |```""".stripMargin,
      )
    } yield ()
  }

  test("twirl-rename") {
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
      _ <- server.assertRename(
        "src/main/twirl/example.scala.html",
        """|@(name: String)
           |<h1>Hello @na@@me!</h1>
         """.stripMargin,
        Map(
          "src/main/twirl/example.scala.html" ->
            s"""|@(address: String)
                |<h1>Hello @address!</h1>
          """.stripMargin
        ),
        Set("src/main/twirl/example.scala.html"),
        "address",
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

  test("twirl-import") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|src/main/twirl/example.scala.html
            |@(name: String)
            |<h1>Hello @name @// @@</h1>
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
      _ <- assertCompletionEdit(
        "File@@",
        """
          |
          |@import java.io.File
          |@(name: String)
          |<h1>Hello @name @File</h1>
        """.stripMargin,
        filenameOpt = Some("src/main/twirl/example.scala.html"),
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

      res1 <- definitionsAt(
        "src/main/twirl/example.scala.html",
        """|@(name: String)
           |<h1>Hello @na@@me.toInt</h1>
           |""".stripMargin,
      )

      _ = assert(
        res.head
          .getUri()
          .toString
          .contains("StringLike.scala")
      )

      _ = assert(
        res1.head
          .getUri()
          .toString
          .contains("example.scala.html")
      )

    } yield ()
  }
}
