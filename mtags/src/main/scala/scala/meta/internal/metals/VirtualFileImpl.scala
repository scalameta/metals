package scala.meta.internal.metals

import scala.meta.pc.VirtualFile
import scala.meta.inputs.Input

case class VirtualFileImpl(
    path: String,
    value: String
) extends VirtualFile

object VirtualFileImpl {
  def fromInput(input: Input.VirtualFile): VirtualFileImpl =
    VirtualFileImpl(
      input.path,
      input.value
    )
}
