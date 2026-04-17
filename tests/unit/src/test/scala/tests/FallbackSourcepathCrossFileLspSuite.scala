package tests

import scala.meta.internal.metals.Configs.FallbackSourcepathConfig
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.pc.SourcePathMode

class FallbackSourcepathCrossFileLspSuite
    extends BaseLspSuite("fallback-sourcepath-cross-file") {

  override def userConfig: UserConfiguration =
    super.userConfig.copy(
      fallbackSourcepath = FallbackSourcepathConfig("all-sources"),
      fallbackScalaVersion = Some(V.scala213),
      buildOnChange = false,
      buildOnFocus = false,
    )

  override def serverConfig: MetalsServerConfig =
    super.serverConfig.copy(
      compilers = super.serverConfig.compilers.copy(
        sourcePathMode = SourcePathMode.PRUNED
      )
    )

  override def initializeGitRepo: Boolean = true

  test("hover-across-packages-without-build-target") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/build.sbt
            |scalaVersion := "${V.scala213}"
            |
            |/src/foo/Foo.scala
            |package foo
            |
            |object Foo {
            |  val greeting: String = "hello"
            |}
            |
            |/src/bar/Bar.scala
            |package bar
            |
            |import foo.Foo
            |
            |object Bar {
            |  val msg: String = Foo.greeting
            |}
            |""".stripMargin,
        expectError = true,
      )
      _ <- server.didOpen("src/foo/Foo.scala")
      _ <- server.didOpen("src/bar/Bar.scala")
      _ = assertNoDiagnostics()
      _ <- server.assertHover(
        "src/bar/Bar.scala",
        """|package bar
           |
           |import foo.Foo
           |
           |object Bar {
           |  val msg: String = Fo@@o.greeting
           |}
           |""".stripMargin,
        """|```scala
           |object foo.Foo
           |```
           |""".stripMargin,
      )
    } yield ()
  }

  test("completion-across-packages-without-build-target") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/build.sbt
            |scalaVersion := "${V.scala213}"
            |
            |/src/foo/Foo.scala
            |package foo
            |
            |object Foo {
            |  val greeting: String = "hello"
            |}
            |
            |/src/bar/Bar.scala
            |package bar
            |
            |import foo.Foo
            |
            |object Bar {
            |  val x = Foo.greeting
            |}
            |""".stripMargin,
        expectError = true,
      )
      _ <- server.didOpen("src/foo/Foo.scala")
      _ <- server.didOpen("src/bar/Bar.scala")
      _ = assertNoDiagnostics()
      _ <- server.completionList("src/bar/Bar.scala", "Foo.@@").map { list =>
        val text = server.formatCompletion(
          list,
          includeDetail = true,
          filter = _.contains("greeting"),
        )
        assertNoDiff(
          text,
          """|greeting: String
             |""".stripMargin,
        )
      }
    } yield ()
  }
}
