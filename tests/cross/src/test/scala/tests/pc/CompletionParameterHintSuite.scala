package tests.pc

import scala.meta.internal.pc.PresentationCompilerConfigImpl
import scala.meta.pc.PresentationCompilerConfig

import tests.BaseCompletionSuite

class CompletionParameterHintSuite extends BaseCompletionSuite {

  override def ignoreScalaVersion: Option[IgnoreScalaVersion] =
    Some(IgnoreScala3)

  override def config: PresentationCompilerConfig =
    PresentationCompilerConfigImpl(
      _parameterHintsCommand = Some("hello")
    )

  checkItems(
    "command",
    """
      |object Main {
      |  "".stripSuffi@@
      |}
    """.stripMargin,
    { case Seq(item) =>
      item.getCommand.getCommand == "hello"
    },
  )

  checkItems(
    "command",
    """
      |object Main {
      |  println@@
      |}
    """.stripMargin,
    { case Seq(item1, item2) =>
      item1.getCommand == null &&
      item2.getCommand.getCommand == "hello"
    },
  )
}
