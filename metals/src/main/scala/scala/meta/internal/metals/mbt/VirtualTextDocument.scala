package scala.meta.internal.metals.mbt

import java.net.URI
import java.{util => ju}
import javax.tools.JavaFileObject.Kind
import javax.tools.SimpleJavaFileObject

import scala.util.control.NonFatal

import scala.meta.inputs.Input
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.Mtags
import scala.meta.internal.semanticdb.Scala.DescriptorParser
import scala.meta.internal.semanticdb.SymbolInformation.Kind.CLASS
import scala.meta.internal.semanticdb.SymbolInformation.Kind.OBJECT
import scala.meta.internal.semanticdb.SymbolInformation.Kind.PACKAGE_OBJECT
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
    pkg: String,
    _toplevelSymbols: collection.Seq[String],
) extends SimpleJavaFileObject(_uri, Kind.SOURCE)
    with SemanticdbCompilationUnit {
  override def packageSymbol(): String = pkg
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
  private def isToplevelSymbol(info: s.SymbolInformation): Boolean = {
    info.kind match {
      case PACKAGE_OBJECT => true
      case CLASS | OBJECT =>
        try {
          val (_, owner) = DescriptorParser(info.symbol)
          owner.endsWith("/")
        } catch {
          case NonFatal(_) => false
        }
      case _ => false
    }
  }
  def fromDocument(
      language: pc.Language,
      pkg: String,
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
      pkg,
      toplevelSymbols,
    )
  }

  def fromText(
      mtags: Mtags,
      language: pc.Language,
      uri: URI,
      text: String,
  ): VirtualTextDocument = {
    val input = Input.VirtualFile(uri.toString(), text)
    val doc = mtags.indexToplevelSymbols(
      input.toJLanguage,
      input,
      meta.dialects.Scala213,
    )
    val toplevelSymbols = doc.symbols.collect {
      case s if isToplevelSymbol(s) => s.symbol
    }
    val pkg = toplevelSymbols.headOption
      .map { sym =>
        val (_, owner) = DescriptorParser(sym)
        owner
      }
      .getOrElse("_root_")

    VirtualTextDocument(uri, language, text, pkg, toplevelSymbols)
  }
}
