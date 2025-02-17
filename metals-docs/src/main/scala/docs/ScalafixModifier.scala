package docs

import scala.meta.inputs.Input

import mdoc.Reporter
import mdoc.StringModifier

class ScalafixModifier extends StringModifier {
  override val name: String = "scalafix"

  override def process(
      info: String,
      code: Input,
      reporter: Reporter,
  ): String = {

    val shortcut = info match {
      case "vscode" =>
        " In VS Code can be also run using the default shortcut of `shift + alt + ctrl + o`. "
      case _ => ""
    }

    s"""|## Running scalafix rules
        |
        |Scalafix allows users to specify some refactoring and linting rules that can be applied to your
        |codebase. Please checkout the [scalafix website](https://scalacenter.github.io/scalafix) for more information.
        |
        |Since Metals v0.11.7 it's now possible to run scalafix rules using a special
        |command `metals.scalafix-run`.${shortcut}
        |This should run all the rules defined in your `.scalafix.conf` file. All built-in rules
        |and the [community hygiene ones](https://scalacenter.github.io/scalafix/docs/rules/community-rules.html#hygiene-rules) can
        |be run without any additional settings. However, for all the other rules users need to
        |add an additional dependency in the `metals.scalafixRulesDependencies` user setting.
        |Those rules need to be in form of strings such as `com.github.liancheng::organize-imports:0.6.0`, which
        |follows the same convention as [coursier dependencies](https://get-coursier.io/).
        |
        |A sample scalafix configuration can be seen below:
        |
        |```hocon
        |rules = [
        |  OrganizeImports,
        |  ExplicitResultTypes,
        |  RemoveUnused
        |]
        |
        |RemoveUnused.imports = false
        |
        |OrganizeImports.groupedImports = Explode
        |OrganizeImports.expandRelative = true
        |OrganizeImports.removeUnused = true
        |OrganizeImports.groups = [
        |  "re:javax?\\."
        |  "scala."
        |  "scala.meta."
        |  "*"
        |]
        |
        |```
        |
        |""".stripMargin
  }

}
