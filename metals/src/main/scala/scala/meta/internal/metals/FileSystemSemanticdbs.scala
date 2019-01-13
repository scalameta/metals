package scala.meta.internal.metals

import java.nio.charset.Charset
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.Md5Fingerprints
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.mtags.TextDocumentLookup
import scala.meta.io.AbsolutePath

/**
 * Reads SemanticDBs from disk that are produces by the semanticdb-scalac compiler plugin.
 */
final class FileSystemSemanticdbs(
    buildTargets: BuildTargets,
    charset: Charset,
    workspace: AbsolutePath,
    fingerprints: Md5Fingerprints
) extends Semanticdbs {

  override def textDocument(file: AbsolutePath): TextDocumentLookup = {
    if (!file.toLanguage.isScala ||
      file.toNIO.getFileSystem != workspace.toNIO.getFileSystem) {
      TextDocumentLookup.NotFound(file)
    } else {
      semanticdbTargetroot(file) match {
        case Some(targetroot) =>
          Semanticdbs.loadTextDocument(
            file,
            workspace,
            charset,
            fingerprints,
            semanticdbRelativePath => {
              val semanticdbpath = targetroot.resolve(semanticdbRelativePath)
              if (semanticdbpath.isFile) Some(semanticdbpath)
              else None
            }
          )
        case None =>
          TextDocumentLookup.NotFound(file)
      }
    }
  }

  /**
   * Returns the directory containing SemanticDB files for this Scala source file.
   */
  private def semanticdbTargetroot(
      scalaPath: AbsolutePath
  ): Option[AbsolutePath] = {
    for {
      buildTarget <- buildTargets.inverseSources(scalaPath)
      scalacOptions <- buildTargets.scalacOptions(buildTarget)
    } yield scalacOptions.targetroot
  }
}
