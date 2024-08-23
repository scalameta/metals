package scala.meta.internal.pc

import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.core.Comments.Comment

object Compat:
  val EvidenceParamName = dotty.tools.dotc.core.NameKinds.ContextBoundParamName
  extension (unit: CompilationUnit)
    def getComments(): List[Comment] = unit.comments
