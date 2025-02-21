package tests.feature

import scala.meta.internal.metals.codeactions.ConvertToEnumCodeAction

import org.eclipse.lsp4j.CodeAction
import tests.codeactions.BaseCodeActionLspSuite

class ConvertToEnumCrossSuite extends BaseCodeActionLspSuite("convertToEnum") {
  override protected val scalaVersion: String = "3.3.3"

  val filterAction: CodeAction => Boolean =
    _.getTitle().startsWith("Convert sealed")

  check(
    "basic",
    """|sealed trait <<C>>ow
       |object Cow:
       |  class HolsteinFriesian extends Cow
       |  class Highland extends Cow
       |  class BrownSwiss extends Cow
       |""".stripMargin,
    ConvertToEnumCodeAction.title("Cow", isTrait = true),
    """|enum Cow:
       |  case HolsteinFriesian, Highland, BrownSwiss
       |""".stripMargin,
  )
}
