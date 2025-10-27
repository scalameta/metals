package tests.j

import java.{util => ju}

import scala.collection.mutable

import scala.meta.internal.jsemanticdb.Semanticdb
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.mbt.VirtualTextDocument
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
  lazy val docs: mutable.Buffer[VirtualTextDocument] = root.listRecursive
    .filter(_.isFile)
    .filter(f => languages.contains(f.toJLanguage))
    .map(f =>
      VirtualTextDocument
        .fromText(mtags, f.toJLanguage.toPCLanguage, f.toURI, f.readText)
    )
    .toBuffer

  override def listPackage(pkg: String): ju.List[SemanticdbCompilationUnit] = {
    docs.filter(_.pkg == pkg).asJava.widen
  }
}
