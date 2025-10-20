package scala.meta.internal.jpc

import java.util

import scala.meta.pc.SemanticdbCompilationUnit
import scala.meta.pc.SemanticdbFileManager

object EmptySemanticdbFileManager extends SemanticdbFileManager {
  def listPackage(pkg: String): util.List[SemanticdbCompilationUnit] =
    util.Collections.emptyList()
}
