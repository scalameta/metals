package tests.worksheets

import scala.meta.internal.metals.InitializationOptions
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.{BuildInfo => V}

import tests.BaseLspSuite

class WorksheetInfiniteLoopSuite
    extends BaseLspSuite("worksheet-infinite-loop") {

  override protected def initializationOptions: Option[InitializationOptions] =
    Some(
      InitializationOptions.Default.copy(
        decorationProvider = Some(true)
      )
    )

  override def userConfig: UserConfiguration =
    super.userConfig.copy(
      worksheetScreenWidth = 40,
      worksheetCancelTimeout = 1,
    )

  override def serverConfig: MetalsServerConfig =
    MetalsServerConfig.default.copy(
      worksheetTimeout = 8
    )

  // Databricks: ignore for now, no support for worksheets
  test("infinite-loop".ignore, maxRetry = 3) {
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{"a": {"scalaVersion": "${V.scala3}"}}
           |/a/src/main/scala/foo/Main.worksheet.sc
           |class Animal(age: Int)
           |
           |class Rabbit(age: Int) extends Animal(age):
           |  val id = Rabbit.tag
           |  Rabbit.tag += 1
           |  def getId = id
           |
           |object Rabbit:
           |  val basic = Rabbit(0)
           |  var tag: Int = 0
           |
           |val peter = Rabbit(2)
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/foo/Main.worksheet.sc")
      _ = assertNoDiff(
        client.workspaceShowMessages,
        Messages.worksheetTimeout,
      )
      _ <- server.didChange("a/src/main/scala/foo/Main.worksheet.sc")(_ =>
        "val a = 1"
      )
      _ <- server.didSave("a/src/main/scala/foo/Main.worksheet.sc")
      _ <- server.assertInlayHints(
        "a/src/main/scala/Main.worksheet.sc",
        """|val a = 1/* // : Int = 1*/
           |""".stripMargin,
      )
    } yield ()
  }
}
