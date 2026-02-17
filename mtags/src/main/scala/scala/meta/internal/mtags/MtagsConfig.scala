package scala.meta.internal.mtags

import scala.meta.inputs.Input
import scala.meta.internal.metals.ReportContext
import scala.meta.internal.mtags.proto.ProtoMtagsV1
import scala.meta.internal.mtags.proto.ProtoMtagsV2
import scala.meta.internal.{semanticdb => s}

final case class MtagsConfig(
    useQdox: Boolean = false,
    /**
     * If true (default), use ProtoMtagsV1 (scanner-based) for proto indexing.
     * If false, use ProtoMtagsV2 (Java parser-based).
     *
     * V1 uses a hand-written scanner that handles malformed proto files gracefully.
     * V2 uses the Java proto parser library which fails on syntax errors.
     *
     * V2 is intended to be a superset of V1 (generating both proto-package and
     * java-package symbols), but currently V1 is more robust for proto files
     * with syntax errors. See plans/protopc.md for details.
     *
     * TODO: Make V2 handle syntax errors gracefully, then deprecate V1.
     */
    useProtoV1: Boolean = true
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
  def protoInstance(
      input: Input.VirtualFile,
      includeGeneratedSymbols: Boolean = false,
      includeFuzzyReferences: Boolean = false
  ): MtagsIndexer = {
    if (useProtoV1) {
      new ProtoMtagsV1(input, includeGeneratedSymbols, includeFuzzyReferences)
    } else {
      // V2 doesn't have these parameters - it always includes members
      new ProtoMtagsV2(input)
    }
  }
  def protoInstanceWithOccurrenceVisitor(
      input: Input.VirtualFile,
      includeGeneratedSymbols: Boolean = false
  )(
      onOccurrence: (s.SymbolOccurrence, s.SymbolInformation, String) => Unit
  ): MtagsIndexer = {
    if (useProtoV1) {
      new ProtoMtagsV1(input, includeGeneratedSymbols) {
        override def visitOccurrence(
            occ: s.SymbolOccurrence,
            info: s.SymbolInformation,
            owner: String
        ): Unit = {
          onOccurrence(occ, info, owner)
        }
      }
    } else {
      new ProtoMtagsV2(input) {
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
