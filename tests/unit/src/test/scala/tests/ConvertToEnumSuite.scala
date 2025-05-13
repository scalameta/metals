package tests

import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.codeactions.ConvertToEnumCodeAction

import org.eclipse.lsp4j.CodeAction
import tests.codeactions.BaseCodeActionLspSuite

class ConvertToEnumSuite extends BaseCodeActionLspSuite("convertToEnum") {
  override protected val scalaVersion: String = BuildInfo.latestScala3Next

  val filterAction: CodeAction => Boolean =
    _.getTitle().startsWith("Convert sealed")

  check(
    "basic".ignore, // not yet implemented
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
