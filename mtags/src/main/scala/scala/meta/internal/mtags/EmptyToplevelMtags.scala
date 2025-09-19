package scala.meta.internal.mtags

import scala.meta.inputs.Input
import scala.meta.internal.semanticdb.Language

class EmptyToplevelMtags(val input: Input.VirtualFile) extends MtagsIndexer {

  override def language: Language = Language.UNKNOWN_LANGUAGE

  override def indexRoot(): Unit = ()

}
