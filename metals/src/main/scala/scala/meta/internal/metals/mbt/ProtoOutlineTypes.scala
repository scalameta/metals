package scala.meta.internal.metals.mbt

import scala.meta.dialects
import scala.meta.inputs.Input
import scala.meta.internal.jsemanticdb.Semanticdb
import scala.meta.internal.mtags.Mtags
import scala.meta.internal.mtags.Symbol

object ProtoOutlineTypes {

  /**
   * Every type the given outlines declare, nested ones included:
   * `com/example/User`, `com/example/User$Builder`,
   * `com/example/UserOrBuilder`.
   *
   * An outline names only the type its file is named after, while Turbine emits
   * one classfile per type declared in it, so the outlines are indexed to
   * recover the rest.
   *
   * It is their own Java text that is indexed, held from when they were
   * generated rather than regenerated from the `.proto`. So a proto that has
   * changed or gone reports what the compiled output holds, which is what has
   * to be hidden, and a deleted proto needs no file on disk for it.
   */
  def declaredBy(
      outlines: Seq[VirtualTextDocument],
      mtags: Mtags,
  ): Seq[String] =
    for {
      outline <- outlines
      document = mtags.indexMBT(
        Semanticdb.Language.JAVA,
        Input.VirtualFile(outline.uri().toString, outline.text),
        dialects.Scala3,
      )
      info <- document.symbols
      symbol = Symbol(info.symbol)
      if symbol.isType && !symbol.isTypeParameter
    } yield symbol.binaryName
}
