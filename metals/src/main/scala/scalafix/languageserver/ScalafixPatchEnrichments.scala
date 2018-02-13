package scalafix.languageserver

import scala.collection.immutable.Seq
import scala.meta.metals.ScalametaEnrichments._
import org.langmeta.lsp.TextEdit
import scalafix.SemanticdbIndex
import scalafix.internal.patch.ImportPatchOps
import scalafix.internal.patch.ReplaceSymbolOps
import scalafix.internal.util.Failure
import scalafix.internal.util.TokenOps
import scalafix.patch.Concat
import scalafix.patch.EmptyPatch
import scalafix.patch.LintPatch
import scalafix.patch.Patch
import scalafix.patch.TokenPatch
import scalafix.patch.TreePatch.ImportPatch
import scalafix.patch.TreePatch.ReplaceSymbol
import scalafix.rule.RuleCtx

// Copy-pasta from scalafix because all of these methods are private.
// We should expose a package private API to get a list of token patches from
// a Patch.
// TODO(olafur): Figure out how to expose a minimal public API in scalafix.Patch
// that supports this use-case.
// All of the copy-paste below could be avoided with a single:
//   Patch.toTokenPatches(Patch): Iterable[TokenPatch]
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
      val mergedTokenPatches = tokenPatches(patch)
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
  private def tokenPatches(
      patch: Patch
  )(implicit ctx: RuleCtx, index: SemanticdbIndex): Iterable[TokenPatch] = {
    val base = underlying(patch)
    val moveSymbol = underlying(
      ReplaceSymbolOps.naiveMoveSymbolPatch(base.collect {
        case m: ReplaceSymbol => m
      })
    )
    val patches = base.filterNot(_.isInstanceOf[ReplaceSymbol]) ++ moveSymbol
    val tokenPatches = patches.collect { case e: TokenPatch => e }
    val importPatches = patches.collect { case e: ImportPatch => e }
    val importTokenPatches = {
      val result = ImportPatchOps.superNaiveImportPatchToTokenPatchConverter(
        ctx,
        importPatches
      )
      underlying(result.asPatch)
        .collect {
          case x: TokenPatch => x
          case els =>
            throw Failure.InvariantFailedException(
              s"Expected TokenPatch, got $els"
            )
        }
    }
    importTokenPatches ++ tokenPatches
  }
  private def underlying(patch: Patch): Seq[Patch] = {
    val builder = Seq.newBuilder[Patch]
    foreach(patch) {
      case _: LintPatch =>
      case els =>
        builder += els
    }
    builder.result()
  }
  private def foreach(patch: Patch)(f: Patch => Unit): Unit = {
    def loop(patch: Patch): Unit = patch match {
      case Concat(a, b) =>
        loop(a)
        loop(b)
      case EmptyPatch => // do nothing
      case els =>
        f(els)
    }
    loop(patch)
  }

  import scalafix.patch.TokenPatch._
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
