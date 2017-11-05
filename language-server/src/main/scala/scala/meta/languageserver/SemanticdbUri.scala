package scala.meta.languageserver

import java.net.URI
import java.nio.file.Paths
import scala.meta.Source
import org.langmeta.io.AbsolutePath

/** Extractor for uris ending with `.semanticdb` */
object SemanticdbUri {
  def unapply(arg: String): Option[AbsolutePath] = {
    if (arg.endsWith(".semanticdb") && arg.startsWith("file:")) {
      Some(AbsolutePath(Paths.get(new URI(arg))))
    } else None
  }
}

