package scala.meta.internal.metals.debug
import tests.FileLayout

final class DebugWorkspaceLayout(val files: List[DebugFileLayout]) {
  override def toString: String = {
    files.mkString
  }
}

object DebugWorkspaceLayout {
  def apply(layout: String): DebugWorkspaceLayout = {
    val files = FileLayout.mapFromString(layout).toList.map {
      case (name, originalContent) => DebugFileLayout(name, originalContent)
    }

    new DebugWorkspaceLayout(files)
  }
}
