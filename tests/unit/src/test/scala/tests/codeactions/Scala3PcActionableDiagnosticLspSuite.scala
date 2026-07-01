package tests.codeactions

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.TextEdits
import scala.meta.internal.metals.UserConfiguration

import tests.BaseLspSuite

class Scala3PcActionableDiagnosticLspSuite
    extends BaseLspSuite("scala3-pc-actionable-diagnostic") {

  override def userConfig: UserConfiguration =
    super.userConfig.copy(
      fallbackScalaVersion = Some(BuildInfo.latestScala3Next),
      presentationCompilerDiagnostics = true,
      buildOnChange = false,
      buildOnFocus = false,
    )

  override def initializeGitRepo: Boolean = true

  test("remove-repeated-modifier") {
    cleanWorkspace()
    val file = "src/Repeated.scala"
    for {
      _ <- initialize(
        s"""|/build.sbt
            |scalaVersion := "${BuildInfo.scala213}"
            |
            |/$file
            |private private val x = 1
            |""".stripMargin,
        expectError = true,
      )
      _ <- server.didOpen(file)
      diagnosticsPublished = server.awaitNextDiagnostics(file, _.nonEmpty)
      _ <- server.didFocus(file)
      _ <- diagnosticsPublished
      codeActions <- server.assertCodeAction(
        file,
        "<<private private val x = 1>>\n",
        """|Remove repeated modifier: "private"
           |""".stripMargin,
        kind = Nil,
        filterAction = _.getTitle.startsWith("Remove repeated modifier"),
      )
      edits = codeActions.head.getEdit.getChanges.asScala.values
        .flatMap(_.asScala)
        .toList
      _ = assertNoDiff(
        TextEdits.applyEdits("private private val x = 1", edits),
        "private  val x = 1",
      )
    } yield ()
  }

  test("other-diagnostics-actions-should-not-leak") {
    cleanWorkspace()
    val file = "src/Repeated.scala"
    for {
      _ <- initialize(
        s"""|/build.sbt
            |scalaVersion := "${BuildInfo.scala213}"
            |
            |/$file
            |private private val x = 1
            |private private val y = 2
            |""".stripMargin,
        expectError = true,
      )
      _ <- server.didOpen(file)
      diagnosticsPublished = server.awaitNextDiagnostics(file, _.size >= 2)
      _ <- server.didFocus(file)
      _ <- diagnosticsPublished
      _ <- server.assertCodeAction(
        file,
        """|<<private private val x = 1>>
           |private private val y = 2
           |""".stripMargin,
        """|Remove repeated modifier: "private"
           |""".stripMargin,
        kind = Nil,
        filterAction = _.getTitle.startsWith("Remove repeated modifier"),
      )
    } yield ()
  }
}
