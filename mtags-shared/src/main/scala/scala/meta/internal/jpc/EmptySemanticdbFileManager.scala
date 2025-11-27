package scala.meta.internal.jpc

import java.nio.file.Path
import java.util

import scala.meta.pc.SemanticdbCompilationUnit
import scala.meta.pc.SemanticdbFileManager

object EmptySemanticdbFileManager extends SemanticdbFileManager {
  def listPackage(pkg: String): util.List[SemanticdbCompilationUnit] =
    util.Collections.emptyList()

  def listAllPackages(): util.Map[String, util.Set[Path]] =
    util.Collections.emptyMap()
}
