package scala.meta.internal.mtags

import scala.meta.inputs.Input
import scala.meta.internal.metals.ReportContext
import scala.meta.internal.{semanticdb => s}

final case class MtagsConfig(
    useQdox: Boolean = false
) {
  def javaInstance(
      input: Input.VirtualFile,
      includeMembers: Boolean,
      includeReferences: Boolean = false
  )(implicit rc: ReportContext): MtagsIndexer = {
    if (useQdox) {
      new QdoxJavaMtags(input, includeMembers)
    } else {
      new JavacMtags(
        input,
        includeMembers,
        includeFuzzyReferences = includeReferences
      )
    }
  }
  def javaInstanceWithOccurrenceVisitor(
      virtualFile: Input.VirtualFile,
      includeMembers: Boolean,
      includeReferences: Boolean
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
      val emptyInfo = s.SymbolInformation()
      new JavacMtags(
        virtualFile,
        includeMembers,
        includeFuzzyReferences = includeReferences
      ) {
        override def visitFuzzyReferenceOccurrence(
            occ: s.SymbolOccurrence
        ): Unit = {
          if (includeReferences) {
            onOccurrence(occ, emptyInfo, owner)
          }
        }
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
