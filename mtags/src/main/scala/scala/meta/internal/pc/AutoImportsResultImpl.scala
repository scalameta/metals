package scala.meta.internal.pc

import scala.meta.pc.AutoImportsResult
import java.{util => ju}
import org.eclipse.lsp4j.TextEdit

case class AutoImportsResultImpl(packageName: String, edits: ju.List[TextEdit])
    extends AutoImportsResult
