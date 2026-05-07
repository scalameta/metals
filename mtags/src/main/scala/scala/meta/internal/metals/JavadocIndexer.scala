package scala.meta.internal.metals

import java.util

import scala.util.control.NonFatal

import scala.meta.inputs.Input
import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.mtags.JavacMtags
import scala.meta.internal.semanticdb.Scala.Descriptor
import scala.meta.internal.semanticdb.Scala.Symbols
import scala.meta.pc.ContentType
import scala.meta.pc.ContentType.MARKDOWN
import scala.meta.pc.ContentType.PLAINTEXT
import scala.meta.pc.SymbolDocumentation
import scala.meta.pc.reports.ReportContext
import scala.meta.internal.docstrings.printers.MarkdownGenerator

/**
 * Extracts Javadoc from Java source code.
 */
class JavadocIndexer(
    input: Input.VirtualFile,
    fn: SymbolDocumentation => Unit,
    contentType: ContentType
)(implicit rc: ReportContext)
    extends JavacMtags(input, includeMembers = true, keepDocComments = true) {

  override protected def onClass(
      sym: String,
      name: String,
      typeParams: List[String],
      docComment: Option[String]
  ): Unit = {
    fn(fromClass(sym, name, typeParams, docComment))
  }

  override protected def onConstructor(
      sym: String,
      params: List[String],
      typeParams: List[String],
      docComment: Option[String]
  ): Unit = {
    fn(fromConstructor(sym, params, typeParams, docComment))
  }

  override protected def onMethod(
      sym: String,
      name: String,
      params: List[String],
      typeParams: List[String],
      docComment: Option[String]
  ): Unit = {
    fn(fromMethod(sym, name, params, typeParams, docComment))
  }

  def toContent(docComment: Option[String]): String = {
    val comment = JavadocParser.extractBody(docComment)
    if (comment.isEmpty) return ""
    contentType match {
      case MARKDOWN =>
        try MarkdownGenerator.fromDocstring(s"/**$comment\n*/", Map.empty)
        catch {
          case NonFatal(_) =>
            // The Scaladoc parser implementation uses fragile regexp processing which
            // sometimes causes exceptions.
            comment
        }
      case PLAINTEXT => comment
    }
  }

  def fromMethod(
      symbol: String,
      name: String,
      params: List[String],
      typeParams: List[String],
      docComment: Option[String]
  ): SymbolDocumentation = {
    new MetalsSymbolDocumentation(
      symbol,
      name,
      toContent(docComment),
      "",
      typeParameters(symbol, typeParams, docComment),
      parameters(symbol, params, docComment)
    )
  }
  def fromClass(
      symbol: String,
      name: String,
      typeParams: List[String],
      docComment: Option[String]
  ): SymbolDocumentation = {
    new MetalsSymbolDocumentation(
      symbol,
      name,
      toContent(docComment),
      "",
      typeParameters(symbol, typeParams, docComment),
      Nil.asJava
    )
  }
  def fromConstructor(
      symbol: String,
      params: List[String],
      typeParams: List[String],
      docComment: Option[String]
  ): SymbolDocumentation = {
    new MetalsSymbolDocumentation(
      symbol,
      "<init>",
      toContent(docComment),
      "",
      typeParameters(symbol, typeParams, docComment),
      parameters(symbol, params, docComment)
    )
  }
  def param(
      symbol: String,
      name: String,
      docstring: String
  ): SymbolDocumentation =
    new MetalsSymbolDocumentation(
      symbol,
      name,
      if (docstring == null) "" else docstring,
      ""
    )
  def typeParameters(
      owner: String,
      typeParams: List[String],
      docComment: Option[String]
  ): util.List[SymbolDocumentation] = {
    val tags = JavadocParser.extractParamTags(docComment)
    typeParams.map { tparam =>
      val docstring = tags.getOrElse(s"<$tparam>", "")
      this.param(
        Symbols.Global(owner, Descriptor.TypeParameter(tparam)),
        tparam,
        docstring
      )
    }.asJava
  }
  def parameters(
      owner: String,
      params: List[String],
      docComment: Option[String]
  ): util.List[SymbolDocumentation] = {
    val tags = JavadocParser.extractParamTags(docComment)
    params.map { param =>
      val docstring = tags.getOrElse(param, "")
      this.param(
        Symbols.Global(owner, Descriptor.Parameter(param)),
        param,
        docstring
      )
    }.asJava
  }

}

object JavadocIndexer {
  def all(
      input: Input.VirtualFile,
      contentType: ContentType
  )(implicit rc: ReportContext): List[SymbolDocumentation] = {
    val buf = List.newBuilder[SymbolDocumentation]
    foreach(input, contentType)(buf += _)
    buf.result()
  }
  def foreach(
      input: Input.VirtualFile,
      contentType: ContentType
  )(fn: SymbolDocumentation => Unit)(implicit rc: ReportContext): Unit = {
    new JavadocIndexer(input, fn, contentType).indexRoot()
  }
}
