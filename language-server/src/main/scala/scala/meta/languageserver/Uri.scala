package scala.meta.languageserver

import java.net.URI
import java.nio.file.Paths
import scala.meta.Source
import org.langmeta.io.AbsolutePath
import org.langmeta.io.RelativePath

/** Extractor for converting stringy vscode uris into absolute paths */
object Uri {
  def toRelative(
      uri: String
  )(implicit cwd: AbsolutePath): Option[RelativePath] =
    toPath(uri).map(_.toRelative(cwd))
  def toPath(uri: String): Option[AbsolutePath] = {
    if (uri.startsWith("file:")) Some(AbsolutePath(Paths.get(new URI(uri))))
    else None
  }
  def unapply(arg: String): Option[AbsolutePath] = toPath(arg)
}
