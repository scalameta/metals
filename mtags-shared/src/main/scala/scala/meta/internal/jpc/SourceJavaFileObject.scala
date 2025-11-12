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

  // HACK: we need a way to recover the original URI
  override def getName(): String = s"originaluri-$originalUri"
  override def toUri(): URI = uri
}

case class PatchedModule(moduleName: String, sourceRoot: String) {
  def asOptions: List[String] = List(
    "--patch-module",
    s"$moduleName=$sourceRoot"
  )
}

object SourceJavaFileObject {

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
