package scala.meta.internal.pc

import org.eclipse.lsp4j.TextEdit

import java.{util => ju}
import scala.meta.pc.AutoImportsResult

case class AutoImportsResultImpl(packageName: String, edits: ju.List[TextEdit])
    extends AutoImportsResult
