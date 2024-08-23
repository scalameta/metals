package scala.meta.internal.pc

import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.core.Comments.Comment

object Compat:
  val EvidenceParamName = dotty.tools.dotc.core.NameKinds.EvidenceParamName

  extension (unit: CompilationUnit)
    def getComments(): List[Comment] = Nil
