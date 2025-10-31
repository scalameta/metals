package tests.codeactions

import scala.meta.internal.metals.codeactions.PatternMatchRefactor
import scala.meta.internal.metals.codeactions.RemoveInfixRefactor

import org.eclipse.lsp4j.CodeAction

class RemoveInfixLspSuite
    extends BaseCodeActionLspSuite(
      "removeInfixApply"
    ) {

  val filterAction: CodeAction => Boolean = { (act: CodeAction) =>
    act.getTitle() == PatternMatchRefactor.convertPatternMatch
  }

  check(
    "simple case",
    """|object Main {
       |  "abc" <<startsWith>> "a"
       |}
       |""".stripMargin,
    RemoveInfixRefactor.title,
    """|object Main {
       |  "abc".startsWith("a")
       |}
       |""".stripMargin,
  )

  check(
    "double case first one selected",
    """|object Main {
       |  "sdf" <<stripMargin>> '|' startsWith "sdf"
       |}
       |""".stripMargin,
    RemoveInfixRefactor.title,
    """|object Main {
       |  "sdf".stripMargin('|') startsWith "sdf"
       |}
       |""".stripMargin,
  )

  check(
    "double case second one selected",
    """|object Main {
       |  "sdf" stripMargin '|' <<startsWith>> "sdf"
       |}
       |""".stripMargin,
    RemoveInfixRefactor.title,
    """|object Main {
       |  ("sdf" stripMargin '|').startsWith("sdf")
       |}
       |""".stripMargin,
  )
}
