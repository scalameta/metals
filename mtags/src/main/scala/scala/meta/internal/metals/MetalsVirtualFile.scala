package scala.meta.internal.metals

import scala.meta.pc.VirtualFile
import scala.meta.inputs.Input

case class MetalsVirtualFile(
    path: String,
    value: String
) extends VirtualFile
object MetalsVirtualFile {
  def fromInput(input: Input.VirtualFile): MetalsVirtualFile =
    MetalsVirtualFile(
      input.path,
      input.value
    )
}
