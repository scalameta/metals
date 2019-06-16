package tests.pc

import scala.meta.internal.pc.PresentationCompilerConfigImpl
import scala.meta.pc.PresentationCompilerConfig
import tests.BaseCompletionSuite
import scala.collection.Seq

object CompletionParameterHintSuite extends BaseCompletionSuite {

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
    """.stripMargin, {
      case Seq(item) =>
        assert(item.getCommand.getCommand == "hello")
    }
  )

  checkItems(
    "command",
    """
      |object Main {
      |  println@@
      |}
    """.stripMargin, {
      case Seq(item1, item2) =>
        assert(item1.getCommand == null)
        assert(item2.getCommand.getCommand == "hello")
    }
  )
}
