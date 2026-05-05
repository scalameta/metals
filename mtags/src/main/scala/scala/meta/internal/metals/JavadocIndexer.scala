package scala.meta.internal.metals

import java.util

import scala.util.control.NonFatal

import scala.meta.inputs.Input
import scala.meta.inputs.Position
import scala.meta.internal.docstrings.printers.MarkdownGenerator
import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.mtags.JavaClassInfo
import scala.meta.internal.mtags.JavaConstructorInfo
import scala.meta.internal.mtags.JavaMethodInfo
import scala.meta.internal.mtags.JavaMtags
import scala.meta.internal.mtags.JavadocComment
import scala.meta.internal.mtags.JavadocParser
import scala.meta.internal.semanticdb.Scala.Descriptor
import scala.meta.internal.semanticdb.Scala.Symbols
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.pc.ContentType
import scala.meta.pc.ContentType.MARKDOWN
import scala.meta.pc.ContentType.PLAINTEXT
import scala.meta.pc.SymbolDocumentation
import scala.meta.pc.reports.ReportContext

/**
 * Extracts Javadoc from Java source code.
 */
class JavadocIndexer(
    input: Input.VirtualFile,
    fn: SymbolDocumentation => Unit,
    contentType: ContentType
)(implicit rc: ReportContext)
    extends JavaMtags(input, includeMembers = true) {

  override def visitClass(
      info: JavaClassInfo,
      pos: Position,
      kind: SymbolInformation.Kind
  ): Unit = {
    super.visitClass(info, pos, kind)
    fn(fromClass(currentOwner, info))
  }

  override def visitConstructor(
      info: JavaConstructorInfo,
      disambiguator: String,
      pos: Position,
      properties: Int
  ): Unit = {
    fn(
      fromConstructor(
        symbol(Descriptor.Method("<init>", disambiguator)),
        info
      )
    )
  }

  override def visitMethod(
      info: JavaMethodInfo,
      name: String,
      disambiguator: String,
      pos: Position,
      properties: Int
  ): Unit = {
    fn(
      fromMethod(
        symbol(Descriptor.Method(name, disambiguator)),
        info
      )
    )
  }

  private def toContent(javadocComment: Option[String]): String = {
    val comment = javadocComment
      .flatMap { raw =>
        JavadocParser.parse(raw).map(_.body)
      }
      .getOrElse("")
    contentType match {
      case MARKDOWN =>
        try MarkdownGenerator.fromDocstring(s"/**$comment\n*/", Map.empty)
        catch {
          case NonFatal(_) => comment
        }
      case PLAINTEXT => comment
    }
  }

  def fromMethod(
      symbol: String,
      info: JavaMethodInfo
  ): SymbolDocumentation = {
    val javadoc = info.javadocComment.flatMap(JavadocParser.parse)
    new MetalsSymbolDocumentation(
      symbol,
      info.name,
      toContent(info.javadocComment),
      "",
      typeParameters(symbol, javadoc, info.typeParameterNames),
      parameters(symbol, javadoc, info.parameterNames)
    )
  }

  def fromClass(
      symbol: String,
      info: JavaClassInfo
  ): SymbolDocumentation = {
    val javadoc = info.javadocComment.flatMap(JavadocParser.parse)
    new MetalsSymbolDocumentation(
      symbol,
      info.name,
      toContent(info.javadocComment),
      "",
      typeParameters(symbol, javadoc, info.typeParameterNames),
      Nil.asJava
    )
  }

  def fromConstructor(
      symbol: String,
      info: JavaConstructorInfo
  ): SymbolDocumentation = {
    val javadoc = info.javadocComment.flatMap(JavadocParser.parse)
    new MetalsSymbolDocumentation(
      symbol,
      info.name,
      toContent(info.javadocComment),
      "",
      typeParameters(symbol, javadoc, info.typeParameterNames),
      parameters(symbol, javadoc, info.parameterNames)
    )
  }

  private def param(
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

  private def typeParameters(
      owner: String,
      javadoc: Option[JavadocComment],
      tparamNames: List[String]
  ): util.List[SymbolDocumentation] = {
    tparamNames.map { tparamName =>
      val tparamTag = s"<$tparamName>"
      val docstring = javadoc.toList
        .flatMap(_.tagsByName("param"))
        .collectFirst {
          case tag if tag.value.startsWith(tparamTag) =>
            tag.value
        }
      this.param(
        Symbols.Global(owner, Descriptor.TypeParameter(tparamName)),
        tparamName,
        docstring.getOrElse("")
      )
    }.asJava
  }

  private def parameters(
      owner: String,
      javadoc: Option[JavadocComment],
      paramNames: List[String]
  ): util.List[SymbolDocumentation] = {
    paramNames.map { paramName =>
      val docstring = javadoc.toList
        .flatMap(_.tagsByName("param"))
        .collectFirst {
          case tag
              if tag.value.startsWith(paramName) &&
                (tag.value.length == paramName.length ||
                  tag.value.charAt(paramName.length).isWhitespace) =>
            tag.value
        }
      this.param(
        Symbols.Global(owner, Descriptor.Parameter(paramName)),
        paramName,
        docstring.getOrElse("")
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
