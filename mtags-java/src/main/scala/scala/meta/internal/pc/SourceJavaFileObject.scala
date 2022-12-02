package scala.meta.internal.pc

import java.net.URI
import javax.tools.JavaFileObject.Kind
import javax.tools.SimpleJavaFileObject

class SourceJavaFileObject(src: String, uri: URI, kind: Kind)
    extends SimpleJavaFileObject(uri, kind) {
  override def getCharContent(ignoreEncodingErrors: Boolean): CharSequence = src
}

object SourceJavaFileObject {
  def make(code: String, uri: URI): SourceJavaFileObject = {
    // parent `javax.tools.SimpleJavaObject` fails if URI doesn't have path
    val relativeUri =
      if (uri.getScheme() == "jar") {
        val parts = uri.getSchemeSpecificPart().split("!")
        if (parts.length == 2) URI.create(parts(1)) else uri
      } else uri

    new SourceJavaFileObject(code, relativeUri, Kind.SOURCE)
  }
}
