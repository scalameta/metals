package tests.j

import java.nio.file.Path
import java.{util => ju}

import scala.collection.mutable

import scala.meta.internal.jsemanticdb.Semanticdb
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.mbt.IndexedDocument
import scala.meta.internal.mtags.Mtags
import scala.meta.io.AbsolutePath
import scala.meta.pc.SemanticdbCompilationUnit
import scala.meta.pc.SemanticdbFileManager

/**
 * Basic SemanticDB file manager that scans all files in a given directory.
 *
 * @param root
 * @param mtags
 */
class TestingSemanticdbFileManager(
    root: AbsolutePath,
    mtags: Mtags,
    languages: Set[Semanticdb.Language] = Set(Semanticdb.Language.JAVA),
) extends SemanticdbFileManager {
  private val buffers = Buffers()
  lazy val docs: mutable.Buffer[IndexedDocument] = root.listRecursive
    .filter(_.isFile)
    .filter(f => languages.contains(f.toJLanguage))
    .map(f =>
      IndexedDocument.fromFile(f, mtags, buffers, meta.dialects.Scala213)
    )
    .toBuffer

  override def listPackage(pkg: String): ju.List[SemanticdbCompilationUnit] = {
    docs
      .collect {
        case doc if doc.semanticdbPackages.contains(pkg) =>
          doc.toSemanticdbCompilationUnit(buffers)
      }
      .asJava
      .widen
  }

  override def listAllPackages(): ju.Map[String, ju.Set[Path]] = {
    ju.Collections.emptyMap()
  }
}
