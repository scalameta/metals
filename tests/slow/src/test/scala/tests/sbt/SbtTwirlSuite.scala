package tests.sbt

import scala.meta.internal.metals.{BuildInfo => V}

import tests.CompletionsAssertions

class SbtTwirlSuite extends SbtServerSuite with CompletionsAssertions {

  val twirlVersion = "2.0.9"
  val playVersion = "3.0.7"

  /**
   * Common build.sbt content for Play Framework test projects.
   * Adds Play as a library dependency alongside sbt-twirl so that
   * the `play_2.13` jar appears in the classpath, triggering Play
   * detection in `SourceMapper`.
   */
  def playBuildSbt: String =
    s"""|enablePlugins(SbtTwirl)
        |Compile / unmanagedSourceDirectories := Seq(
        |  (baseDirectory.value / "src" / "main" / "scala"),
        |  (baseDirectory.value / "src" / "main" / "scala-3"),
        |  (baseDirectory.value / "src" / "main" / "java"),
        |  (baseDirectory.value / "src" / "main" / "twirl")
        |)
        |scalaVersion := "${V.scala213}"
        |libraryDependencies += "org.playframework" %% "play" % "${playVersion}"
        |""".stripMargin

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
           |```
           |Returns the length of this string.
           |The length is equal to the number of [Unicode
           |code units]() in the string.
           |""".stripMargin.trim,
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
        s"""|/project/build.properties
            |sbt.version=${V.sbtVersion}
            |/src/main/twirl/example.scala.html
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
            |scalaVersion := "${V.scala213}"
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
        s"""|/project/build.properties
            |sbt.version=${V.sbtVersion}
            |/src/main/twirl/example.scala.html
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
            |scalaVersion := "${V.scala213}"
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
        s"""|/project/build.properties
            |sbt.version=${V.sbtVersion}
            |/src/main/twirl/example.scala.html
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
            |scalaVersion := "${V.scala213}"
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
          .contains("StringOps.scala")
      )

      _ = assert(
        res1.head
          .getUri()
          .toString
          .contains("example.scala.html")
      )

      _ = assert(
        res1.head.getRange.getStart.getLine == 0,
        s"Expected definition on line 0, got ${res1.head.getRange.getStart.getLine}",
      )

    } yield ()
  }

  test("twirl-definition-code-block") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/project/build.properties
            |sbt.version=${V.sbtVersion}
            |/src/main/twirl/example.scala.html
            |@(name: String)
            |@{
            |  val n = name.toInt
            |}
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

      res <- definitionsAt(
        "src/main/twirl/example.scala.html",
        """|@(name: String)
           |@{
           |  val n = name.toI@@nt
           |}
           |""".stripMargin,
      )

      res1 <- definitionsAt(
        "src/main/twirl/example.scala.html",
        """|@(name: String)
           |@{
           |  val n = na@@me.toInt
           |}
           |""".stripMargin,
      )

      _ = assert(
        res.head
          .getUri()
          .toString
          .contains("StringOps.scala"),
        s"Expected definition of `toInt` in StringOps.scala, got ${res.head.getUri()}",
      )

      _ = assert(
        res1.head
          .getUri()
          .toString
          .contains("example.scala.html"),
        s"Expected definition of `name` in example.scala.html, got ${res1.head.getUri()}",
      )

      _ = assert(
        res1.head.getRange.getStart.getLine == 0,
        s"Expected `name` definition on line 0, got ${res1.head.getRange.getStart.getLine}",
      )

    } yield ()
  }

  test("twirl-hover-code-block") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/project/build.properties
            |sbt.version=${V.sbtVersion}
            |/src/main/twirl/example.scala.html
            |@(name: String)
            |@{
            |  val n = name.toInt
            |}
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
           |@{
           |  val n = name.toI@@nt
           |}
           |""".stripMargin,
        """|```scala
           |def toInt: Int
           |```
           |Parse as an `Int` (string must contain only decimal digits and optional leading `-` or `+`).
           |
           |**Throws**
           |- `java.lang.NumberFormatException`: If the string does not contain a parsable `Int`.""".stripMargin,
      )
    } yield ()
  }

  test("twirl-play-hover") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/project/build.properties
            |sbt.version=${V.sbtVersion}
            |/src/main/twirl/example.scala.html
            |@()(implicit request: Request[AnyContent])
            |<h1>Hello @request.method</h1>
            |/project/plugins.sbt
            |addSbtPlugin("org.playframework.twirl" % "sbt-twirl" % "${twirlVersion}")
            |/build.sbt
            |$playBuildSbt
            |""".stripMargin
      )
      _ <- server.didOpen("src/main/twirl/example.scala.html")
      _ <- server.assertHover(
        "src/main/twirl/example.scala.html",
        """|@()(implicit request: Request[AnyContent])
           |<h1>Hello @request.me@@thod</h1>
           |""".stripMargin,
        """|```scala
           |def method: String
           |```
           |The HTTP method.""".stripMargin,
      )
    } yield ()
  }

  test("twirl-play-definition") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/project/build.properties
            |sbt.version=${V.sbtVersion}
            |/src/main/twirl/example.scala.html
            |@()(implicit request: Request[AnyContent])
            |<h1>Hello @request.method</h1>
            |/project/plugins.sbt
            |addSbtPlugin("org.playframework.twirl" % "sbt-twirl" % "${twirlVersion}")
            |/build.sbt
            |$playBuildSbt
            |""".stripMargin
      )
      _ <- server.didOpen("src/main/twirl/example.scala.html")
      res <- definitionsAt(
        "src/main/twirl/example.scala.html",
        """|@()(implicit request: Request[AnyContent])
           |<h1>Hello @request.me@@thod</h1>
           |""".stripMargin,
      )
      _ = assert(
        {
          val uri = res.head.getUri().toString
          uri.contains("play/api/mvc") &&
          (uri.contains("RequestHeader.scala") || uri.contains("Request.scala"))
        },
        s"Expected definition in Play mvc sources (RequestHeader/Request), got ${res.head.getUri()}",
      )
    } yield ()
  }
}
