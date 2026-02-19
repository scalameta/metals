package tests.sbt

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.{BuildInfo => V}

import tests.CompletionsAssertions

class SbtTwirlSuite extends SbtServerSuite with CompletionsAssertions {

  val twirlVersion = "2.0.9"
  val playVersion = "3.0.10"

  /**
   * Common build.sbt content for Play Framework test projects.
   * Adds Play as a library dependency alongside sbt-twirl so that
   * the `org.playframework:play` dependency module is detected by `SourceMapper`.
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
           |""".stripMargin,
        Map(
          "src/main/twirl/example.scala.html" ->
            """|@(address: String)
               |<h1>Hello @address!</h1>
               |""".stripMargin
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
          |""".stripMargin,
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

      _ = assert(res.length == 1)
      _ = assertNoDiff(
        res.head.getUri().toAbsolutePath.filename,
        "StringOps.scala",
      )

      _ = assert(res1.length == 1)
      _ = assertNoDiff(
        res1.head.getUri().toAbsolutePath.filename,
        "example.scala.html",
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

      _ = assert(res.length == 1)
      _ = assertNoDiff(
        res.head.getUri().toAbsolutePath.filename,
        "StringOps.scala",
      )

      _ = assert(res1.length == 1)
      _ = assertNoDiff(
        res1.head.getUri().toAbsolutePath.filename,
        "example.scala.html",
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

  test("twirl-hover-js") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/project/build.properties
            |sbt.version=${V.sbtVersion}
            |/src/main/twirl/helper.scala.js
            |@(name: String)
            |var x = "@name";
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
      _ <- server.didOpen("src/main/twirl/helper.scala.js")
      _ <- server.assertHover(
        "src/main/twirl/helper.scala.js",
        """|@(name: String)
           |var x = "@na@@me";
           |""".stripMargin,
        """|```scala
           |name: String
           |```""".stripMargin,
      )
    } yield ()
  }

  test("twirl-hover-xml") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/project/build.properties
            |sbt.version=${V.sbtVersion}
            |/src/main/twirl/data.scala.xml
            |@(name: String)
            |<root>@name</root>
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
      _ <- server.didOpen("src/main/twirl/data.scala.xml")
      _ <- server.assertHover(
        "src/main/twirl/data.scala.xml",
        """|@(name: String)
           |<root>@na@@me</root>
           |""".stripMargin,
        """|```scala
           |name: String
           |```""".stripMargin,
      )
    } yield ()
  }

  test("twirl-hover-txt") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/project/build.properties
            |sbt.version=${V.sbtVersion}
            |/src/main/twirl/message.scala.txt
            |@(name: String)
            |Hello @name
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
      _ <- server.didOpen("src/main/twirl/message.scala.txt")
      _ <- server.assertHover(
        "src/main/twirl/message.scala.txt",
        """|@(name: String)
           |Hello @na@@me
           |""".stripMargin,
        """|```scala
           |name: String
           |```""".stripMargin,
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
      _ = assert(res.length == 1)
      _ = {
        val uri = res.head.getUri().toString
        assert(
          uri.contains("play/api/mvc/RequestHeader"),
          s"Expected definition in play/api/mvc/RequestHeader, got $uri",
        )
      }
    } yield ()
  }
}
