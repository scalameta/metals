package tests
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.debug.Source

object DapTestEnrichments {
  implicit class DapXtensionAbsolutePath(path: AbsolutePath) {
    def toDAP: Source = {
      val adaptedPath =
        if (path.isJarFileSystem) path.toURI.toString
        else path.toString()
      val source = new Source
      source.setName(path.filename)
      source.setPath(adaptedPath)
      source
    }
  }

}
