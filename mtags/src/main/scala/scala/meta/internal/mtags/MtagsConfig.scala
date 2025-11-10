package scala.meta.internal.mtags

import scala.meta.inputs.Input
import scala.meta.internal.metals.ReportContext
import scala.meta.internal.{semanticdb => s}

final case class MtagsConfig(
    useQdox: Boolean = false
) {
  def javaInstance(input: Input.VirtualFile, includeMembers: Boolean)(implicit
      rc: ReportContext
  ): MtagsIndexer = {
    if (useQdox) {
      new QdoxJavaMtags(input, includeMembers)
    } else {
      new JavacMtags(input, includeMembers)
    }
  }
  def javaInstanceWithOccurrenceVisitor(
      virtualFile: Input.VirtualFile,
      includeMembers: Boolean
  )(onOccurrence: (s.SymbolOccurrence, s.SymbolInformation, String) => Unit)(
      implicit rc: ReportContext
  ): MtagsIndexer = {
    if (useQdox) {
      new QdoxJavaMtags(virtualFile, includeMembers) {
        override def visitOccurrence(
            occ: s.SymbolOccurrence,
            info: s.SymbolInformation,
            owner: String
        ): Unit = {
          onOccurrence(occ, info, owner)
        }
      }
    } else {
      new JavacMtags(virtualFile, includeMembers) {
        override def visitOccurrence(
            occ: s.SymbolOccurrence,
            info: s.SymbolInformation,
            owner: String
        ): Unit = {
          onOccurrence(occ, info, owner)
        }
      }
    }
  }
}

object MtagsConfig {
  def default: MtagsConfig = MtagsConfig()
}
