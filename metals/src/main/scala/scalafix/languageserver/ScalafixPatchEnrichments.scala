package scalafix.languageserver

import org.langmeta.lsp.TextEdit
import scala.meta.metals.ScalametaEnrichments._
import scalafix.SemanticdbIndex
import scalafix.internal.util.Failure
import scalafix.internal.util.TokenOps
import scalafix.patch.Patch
import scalafix.patch.TokenPatch
import scalafix.rule.RuleCtx

object ScalafixPatchEnrichments {

  implicit class XtensionPatchLSP(val patch: Patch) extends AnyVal {

    /** Converts a scalafix.Patch to precise languageserver.types.TextEdit.
     *
     * We could take a shortcut and apply the patch to a String and return one
     * large TextEdit that replaces the whole file. However, in scalafix
     * we treat each token individually so we can provide more precise changes.
     */
    def toTextEdits(
        implicit ctx: RuleCtx,
        index: SemanticdbIndex
    ): List[TextEdit] = {
      val mergedTokenPatches = Patch
        .treePatchApply(patch)
        .groupBy(x => TokenOps.hash(x.tok))
        .values
        .map(_.reduce(merge))
      mergedTokenPatches.toArray
        .sortBy(_.tok.pos.start)
        .iterator
        .map { tokenPatch =>
          TextEdit(tokenPatch.tok.pos.toRange, tokenPatch.newTok)
        }
        .toList
    }
  }

  import scalafix.patch.TokenPatch._
  // TODO(olafur): fix https://github.com/scalacenter/scalafix/issues/709 so we can remove this copy-pasta
  private def merge(a: TokenPatch, b: TokenPatch): TokenPatch = (a, b) match {
    case (add1: Add, add2: Add) =>
      Add(
        add1.tok,
        add1.addLeft + add2.addLeft,
        add1.addRight + add2.addRight,
        add1.keepTok && add2.keepTok
      )
    case (_: Remove, add: Add) => add.copy(keepTok = false)
    case (add: Add, _: Remove) => add.copy(keepTok = false)
    case (rem: Remove, rem2: Remove) => rem
    case _ => throw Failure.TokenPatchMergeError(a, b)
  }
}
