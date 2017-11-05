package scala.meta.languageserver

import java.net.URI
import java.nio.file.Paths
import scala.meta.Source
import org.langmeta.io.AbsolutePath

/** Extractor for uris ending with `.semanticdb` */
object Uri {
  def toPath(uri: String): Option[AbsolutePath] = {
    if (uri.startsWith("file:")) Some(AbsolutePath(Paths.get(new URI(uri))))
    else None
  }
  def unapply(arg: String): Option[AbsolutePath] = toPath(arg)
}
