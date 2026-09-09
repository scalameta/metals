package scala.meta.internal.metals.mbt

import scala.util.control.NonFatal

import scala.meta.dialects.Scala3
import scala.meta.internal.metals.EmptyReportContext
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags
import scala.meta.internal.semanticdb.XtensionSemanticdbSymbolInformation
import scala.meta.io.AbsolutePath

/**
 * Verifier that uses Compilers to generate semanticdb and verify test classes
 * by checking annotations and class hierarchy.
 *
 * @param compilers Used to generate semanticdb for verification
 * @param mbtWorkspaceSymbolProvider Reserved for future Turbine-based Java verification
 */
object MbtTestClassVerifier {

  def verify(
      candidates: Seq[MbtTestClass],
      workspace: AbsolutePath,
  ): Seq[MbtTestClass] = {
    if (candidates.isEmpty) {
      Seq.empty
    } else {
      for {
        candidate <- candidates
        sourcePath <- Option(candidate.sourcePath)
        if isVerifiedCandidate(workspace, sourcePath, candidate)
      } yield candidate
    }
  }

  private def isVerifiedCandidate(
      workspace: AbsolutePath,
      sourcePath: String,
      candidate: MbtTestClass,
  ): Boolean =
    try {
      isVerified(workspace.resolve(sourcePath), candidate)
    } catch {
      case NonFatal(_) => false
    }

  private def isVerified(
      path: AbsolutePath,
      testClass: MbtTestClass,
  ): Boolean = {
    val doc =
      if (path.isScala) mtags.ScalaMtags.index(path.toInput, Scala3).index()
      else new mtags.JavacMtags(path.toInput)(EmptyReportContext).index()
    val testClassSymbol =
      mtags.Symbol.fromToplevelClassName(testClass.className)
    doc.symbols.find { symbol =>
      symbol.symbol == testClassSymbol.value
    } match {
      case Some(value) if !value.isAbstract =>
        true
      case _ =>
        false
    }

  }

}
