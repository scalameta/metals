package scala.meta.internal.jpc

import java.net.URI
import javax.tools.JavaFileObject.Kind
import javax.tools.SimpleJavaFileObject

import scala.meta.pc.VirtualFileParams

class SourceJavaFileObject(
    src: String,
    originalUri: String,
    uri: URI,
    kind: Kind = Kind.SOURCE,
    val customFilename: Option[String] = None,
    val patchedModule: Option[PatchedModule] = None
) extends SimpleJavaFileObject(uri, kind) {
  override def getCharContent(ignoreEncodingErrors: Boolean): CharSequence = src

  override def getName(): String =
    SourceJavaFileObject.makeOriginalURI(originalUri)
  override def toUri(): URI = uri
}

case class PatchedModule(moduleName: String, sourceRoot: String) {
  def asOptions: List[String] = List(
    "--patch-module",
    s"$moduleName=$sourceRoot"
  )
}

object SourceJavaFileObject {
  // NOTE(olafurpg): This method exists because `JavaFileObject.toUri()` is not
  // a reliable identifier for non-file URIs, for example
  // `jar:file:/path/to/file.jar!/path/to/file.java`.  Instead, we format
  // `.getName()` as "original-ORIGINAL_URI" so that we can recover the original
  // URI later. This is sort of a hack, but I don't know of a cleaner way to
  // communicate URIs to the header-compiler plugin, which does not have access
  // to other Metals APIs, it's a small standalone Java file that can only call
  // `JavaFileObject` APIs.
  def makeOriginalURI(originalURI: String): String = s"originaluri-$originalURI"

  def makeRelativeURI(uri: URI): URI = {
    // parent `javax.tools.SimpleJavaObject` fails if URI doesn't have path
    if (uri.getScheme() == "jar") {
      val parts = uri.getSchemeSpecificPart().split("!")
      if (parts.length == 2) URI.create(parts(1)) else uri
    } else {
      uri
    }
  }
  def fromParams(params: VirtualFileParams): SourceJavaFileObject = {
    make(params.text(), params.uri())
  }
  def make(code: String, uri: URI): SourceJavaFileObject = {
    new SourceJavaFileObject(
      code,
      originalUri = uri.toString,
      uri = makeRelativeURI(uri)
    )
  }
}
