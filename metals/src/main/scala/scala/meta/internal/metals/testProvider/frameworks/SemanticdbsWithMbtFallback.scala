package scala.meta.internal.metals.testProvider.frameworks

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.control.NonFatal

import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.Compilers
import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.mbt.MbtBuildServer
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.io.AbsolutePath

/**
 * Wraps [[() => Semanticdbs]] with a blocking, on-demand presentation-compiler
 * fallback for MBT, since we don't have access to any SemanticDb files on disk
 */
class SemanticdbsWithMbtFallback(
    semanticdbs: () => Semanticdbs,
    buildTargets: BuildTargets,
    compilers: () => Compilers,
) {

  def textDocumentWithMbtFallback(path: AbsolutePath): Option[TextDocument] =
    semanticdbs()
      .textDocument(path)
      .documentIncludingStale
      .orElse(if (isMbt(path)) generateSemanticdbSync(path) else None)

  private def isMbt(path: AbsolutePath): Boolean =
    buildTargets
      .inverseSources(path)
      .flatMap(buildTargets.buildServerOf)
      .exists(connection => MbtBuildServer.isMbtServer(connection.name))

  private def generateSemanticdbSync(path: AbsolutePath): Option[TextDocument] =
    try {
      val documents = Await.result(
        compilers().batchSemanticdbTextDocuments(
          Seq(path),
          EmptyCancelToken,
          timeout = java.time.Duration.ofSeconds(20),
          useFallbackCompiler = true,
          shouldPruneSemanticdb = true,
        ),
        20.seconds,
      )
      documents.documents.find(_.uri.toAbsolutePath == path)
    } catch {
      case NonFatal(e) =>
        scribe.warn(s"Failed to generate semanticdb on demand for $path", e)
        None
    }
}
