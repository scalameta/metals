package tests.codeactions

import scala.meta.internal.metals.codeactions.ConvertToNamedArguments
import scala.meta.internal.metals.codeactions.ExtractValueCodeAction
import scala.meta.internal.metals.codeactions.MillifyDependencyCodeAction
import scala.meta.internal.metals.codeactions.RewriteBracesParensCodeAction
import scala.meta.internal.metals.codeactions.StringActions

class MillifyDependencyLspSuite
    extends BaseCodeActionLspSuite("millifyDependency") {

  check(
    "script-import",
    "import $ivy.\"org.scalameta\" %%<<>> \"metals\" % \"1.0\"",
    MillifyDependencyCodeAction.title("`org.scalameta::metals:1.0`"),
    "import $ivy.`org.scalameta::metals:1.0`",
    fileName = "script.sc",
  )

  check(
    "script-import-cursor-after-import",
    "import $ivy.\"org.scalameta\" %% \"metals\" % \"1.0\"<<>>",
    s"""|${StringActions.multilineTitle}
        |${StringActions.interpolationTitle}
        |${MillifyDependencyCodeAction.title("`org.scalameta::metals:1.0`")}""".stripMargin,
    "import $ivy.`org.scalameta::metals:1.0`",
    fileName = "script.sc",
    selectedActionIndex = 2,
  )

  check(
    "mill-ivy",
    """|import mill._, scalalib._
       |
       |object MyModule extends ScalaModule {
       |  def ivyDeps = Agg(
       |    "org.scalameta" %% "metals" % "1.0"<<>>
       |  )
       |}""".stripMargin,
    s"""|${StringActions.multilineTitle}
        |${StringActions.interpolationTitle}
        |${RewriteBracesParensCodeAction.toBraces("Agg")}
        |${ExtractValueCodeAction.title("\"org.scala` ...")}
        |${ConvertToNamedArguments.title("Agg(...)")}
        |${MillifyDependencyCodeAction.title("ivy\"org.scalameta::metals:1.0\"")}""".stripMargin,
    """|import mill._, scalalib._
       |
       |object MyModule extends ScalaModule {
       |  def ivyDeps = Agg(
       |    ivy"org.scalameta::metals:1.0"
       |  )
       |}""".stripMargin,
    fileName = "build.sc",
    selectedActionIndex = 5,
  )

  checkNoAction(
    "sbt-no-action",
    """|object Main {
       |  "org.scalameta" %%<<>> "metals" % "1.0"
       |}""".stripMargin,
    fileName = "build.sbt",
  )
}
