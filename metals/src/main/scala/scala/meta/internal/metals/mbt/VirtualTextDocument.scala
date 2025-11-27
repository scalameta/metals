package scala.meta.internal.metals.mbt

import java.net.URI
import java.{util => ju}
import javax.tools.JavaFileObject.Kind
import javax.tools.SimpleJavaFileObject

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.{semanticdb => s}
import scala.meta.pc
import scala.meta.pc.SemanticdbCompilationUnit

/**
 * This class is more or less the same as `Input.VirtualFile` but it has a parsed URI instead of a `path: String`.
 */
final case class VirtualTextDocument(
    private val _uri: URI,
    override val language: pc.Language,
    text: String,
    packages: Seq[String],
    _toplevelSymbols: collection.Seq[String],
) extends SimpleJavaFileObject(_uri, Kind.SOURCE)
    with SemanticdbCompilationUnit {

  def pkg: String = packages.headOption.getOrElse("")
  override def packageSymbols(): ju.List[String] = packages.asJava
  override def uri(): URI = _uri
  override def toplevelSymbols(): ju.List[String] = _toplevelSymbols.asJava
  override def binaryName(): String = {
    // Convert SemanticDB symbol "java/io/File#" into a Java FQN "java.io.File"
    _toplevelSymbols.headOption match {
      case None =>
        pkg.replace('/', '.')
      case Some(sym) =>
        sym.stripSuffix("#").stripSuffix(".").replace('/', '.')
    }
  }
  override def getName(): String = uri.toString
  override def getCharContent(ignoreEncodingErrors: Boolean): CharSequence =
    text
  def widen: SimpleJavaFileObject = this
}
object VirtualTextDocument {

  def fromDocument(
      language: pc.Language,
      pkgs: Seq[String],
      toplevelSymbols: collection.Seq[String],
      doc: s.TextDocument,
  ): VirtualTextDocument = {
    if (doc.uri.isEmpty) {
      throw new IllegalArgumentException("TextDocument.uri is empty")
    }
    // A file can exist but be empty
    // if (doc.text.isEmpty) {
    //   throw new IllegalArgumentException(
    //     "TextDocument.text is empty for uri: " + doc.uri
    //   )
    // }
    VirtualTextDocument(
      URI.create(doc.uri),
      language,
      doc.text,
      pkgs,
      toplevelSymbols,
    )
  }
}
