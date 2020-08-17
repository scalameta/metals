package scala.meta.internal.metals

import scala.meta.internal.implementation.TextDocumentWithPath
import scala.meta.internal.metals.codelenses.CodeLens
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.io.AbsolutePath

import org.eclipse.{lsp4j => l}

final class CodeLensProvider(
    codeLensProviders: List[CodeLens],
    semanticdbs: Semanticdbs,
    stacktraceAnalyzer: StacktraceAnalyzer
) {
  // code lenses will be refreshed after compilation or when workspace gets indexed
  def findLenses(path: AbsolutePath): Seq[l.CodeLens] = {
    if (stacktraceAnalyzer.isStackTraceFile(path)) {
      stacktraceAnalyzer.stacktraceLenses(path)
    } else {
      semanticdbs
        .textDocument(path)
        .documentIncludingStale
        .map { textDocument =>
          val doc = TextDocumentWithPath(textDocument, path)
          codeLensProviders.filter(_.isEnabled).flatMap(_.codeLenses(doc))
        }
        .getOrElse(Seq.empty)
    }
  }
}
