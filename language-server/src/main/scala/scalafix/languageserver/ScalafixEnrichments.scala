package scalafix.languageserver

import scala.meta.Tree
import scalafix.Rule
import scalafix.internal.config.ScalafixConfig
import scalafix.lint.LintMessage
import scalafix.patch.Patch
import scalafix.rule.RuleCtx
import scalafix.rule.RuleName

// TODO(olafur) figure out how to refactor scalafix api so this is no longer needed.
object ScalafixEnrichments {
  implicit class XtensionRuleCtxLSP(val `_`: RuleCtx.type) extends AnyVal {
    def applyInternal(tree: Tree, config: ScalafixConfig): RuleCtx =
      RuleCtx(tree, config)
  }
  implicit class XtensionPatchLSP(val `_`: Patch.type) extends AnyVal {
    def lintMessagesInternal(patch: Patch): List[LintMessage] =
      Patch.lintMessages(patch)
  }
  implicit class XtensionRuleLSP(val rule: Rule) extends AnyVal {
    def fixWithNameInternal(ctx: RuleCtx): Map[RuleName, Patch] =
      rule.fixWithName(ctx)
  }
}
