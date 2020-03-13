package scala.meta.internal.metals

import org.eclipse.{lsp4j => l}
import scala.concurrent.ExecutionContext
import scala.meta.internal.implementation.TextDocumentWithPath
import scala.meta.internal.metals.codelenses.CodeLenses
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.io.AbsolutePath

trait CodeLensProvider {
  def findLenses(path: AbsolutePath): Seq[l.CodeLens]
}

final class DebugCodeLensProvider(
    codeLensProviders: List[CodeLenses],
    semanticdbs: Semanticdbs
) extends CodeLensProvider {
  // code lenses will be refreshed after compilation or when workspace gets indexed
  def findLenses(path: AbsolutePath): Seq[l.CodeLens] = {
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

object CodeLensProvider {
  private val Empty: CodeLensProvider = (_: AbsolutePath) => Nil

  def apply(
      codeLensProviders: List[CodeLenses],
      semanticdbs: Semanticdbs,
      capabilities: ClientExperimentalCapabilities
  )(implicit ec: ExecutionContext): CodeLensProvider = {
    if (!capabilities.debuggingProvider) Empty
    else {
      new DebugCodeLensProvider(
        codeLensProviders,
        semanticdbs
      )
    }
  }
}
