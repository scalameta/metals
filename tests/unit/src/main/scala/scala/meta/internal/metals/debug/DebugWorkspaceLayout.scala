package scala.meta.internal.metals.debug
import scala.meta.io.AbsolutePath

import tests.FileLayout

final class DebugWorkspaceLayout(val filesBreakpoints: List[FileBreakpoints]) {
  override def toString: String = {
    filesBreakpoints.mkString
  }
}

object DebugWorkspaceLayout {
  def apply(layout: String, root: AbsolutePath): DebugWorkspaceLayout = {
    val files = FileLayout.mapFromString(layout).toList.map {
      case (name, originalContent) =>
        FileBreakpoints(name, originalContent, root)
    }

    new DebugWorkspaceLayout(files)
  }
}
