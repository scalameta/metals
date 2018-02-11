package scalafix.languageserver

import scala.meta.Tree
import scala.meta.metals.ScalametaEnrichments._
import scala.{meta => m}
import scalafix.Rule
import scalafix.SemanticdbIndex
import scalafix.internal.config.ScalafixConfig
import scalafix.internal.util.EagerInMemorySemanticdbIndex
import scalafix.lint.LintMessage
import scalafix.lint.LintSeverity
import scalafix.patch.Patch
import scalafix.rule.RuleCtx
import scalafix.rule.RuleName
import org.langmeta.{lsp => l}

object ScalafixEnrichments {
  implicit class XtensionLintMessageLSP(val msg: LintMessage) extends AnyVal {
    def toLSP: l.Diagnostic =
      l.Diagnostic(
        range = msg.position.toRange,
        severity = Some(msg.category.severity.toLSP),
        code = Some(msg.category.id),
        source = Some("scalafix"),
        message = msg.message
      )
  }
  implicit class XtensionLintSeverityLSP(val severity: LintSeverity)
      extends AnyVal {
    def toLSP: l.DiagnosticSeverity = severity match {
      case LintSeverity.Error => l.DiagnosticSeverity.Error
      case LintSeverity.Warning => l.DiagnosticSeverity.Warning
      case LintSeverity.Info => l.DiagnosticSeverity.Information
    }
  }
  implicit class XtensionRuleCtxLSP(val `_`: RuleCtx.type) extends AnyVal {
    def applyInternal(tree: Tree, config: ScalafixConfig): RuleCtx =
      RuleCtx(tree, config)
  }
  implicit class XtensionPatchLSPObject(val `_`: Patch.type) extends AnyVal {
    def lintMessagesInternal(
        patches: Map[RuleName, Patch],
        ctx: RuleCtx
    ): List[LintMessage] =
      Patch.lintMessages(patches, ctx)
  }
  implicit class XtensionRuleLSP(val rule: Rule) extends AnyVal {
    def fixWithNameInternal(ctx: RuleCtx): Map[RuleName, Patch] =
      rule.fixWithName(ctx)
  }
  implicit class XtensionSemanticdbIndexObject(val `_`: SemanticdbIndex.type)
      extends AnyVal {
    def load(document: m.Document): SemanticdbIndex =
      EagerInMemorySemanticdbIndex(
        m.Database(document :: Nil),
        m.Sourcepath(Nil),
        m.Classpath(Nil)
      )
  }
}
