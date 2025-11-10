package scala.meta.internal.metals

import java.util

import scala.util.control.NonFatal

import scala.meta.inputs.Input
import scala.meta.inputs.Position
import scala.meta.internal.docstrings.printers.MarkdownGenerator
import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.mtags.QdoxJavaMtags
import scala.meta.internal.semanticdb.Scala.Descriptor
import scala.meta.internal.semanticdb.Scala.Symbols
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.pc.ContentType
import scala.meta.pc.ContentType.MARKDOWN
import scala.meta.pc.ContentType.PLAINTEXT
import scala.meta.pc.SymbolDocumentation

import com.thoughtworks.qdox.model.JavaAnnotatedElement
import com.thoughtworks.qdox.model.JavaClass
import com.thoughtworks.qdox.model.JavaConstructor
import com.thoughtworks.qdox.model.JavaGenericDeclaration
import com.thoughtworks.qdox.model.JavaMethod
import com.thoughtworks.qdox.model.JavaParameter
import com.thoughtworks.qdox.model.JavaTypeVariable

/**
 * Extracts Javadoc from Java source code.
 */
class JavadocIndexer(
    input: Input.VirtualFile,
    fn: SymbolDocumentation => Unit,
    contentType: ContentType
)(implicit rc: ReportContext)
    extends QdoxJavaMtags(input, includeMembers = true) {
  override def visitClass(
      cls: JavaClass,
      pos: Position,
      kind: SymbolInformation.Kind
  ): Unit = {
    super.visitClass(cls, pos, kind)
    fn(fromClass(currentOwner, cls))
  }
  override def visitConstructor(
      ctor: JavaConstructor,
      disambiguator: String,
      pos: Position,
      properties: Int
  ): Unit = {
    fn(
      fromConstructor(
        symbol(Descriptor.Method("<init>", disambiguator)),
        ctor
      )
    )
  }
  override def visitMethod(
      method: JavaMethod,
      name: String,
      disambiguator: String,
      pos: Position,
      properties: Int
  ): Unit = {
    fn(
      fromMethod(
        symbol(Descriptor.Method(name, disambiguator)),
        method
      )
    )
  }

  def toContent(e: JavaAnnotatedElement): String = {
    val comment = Option(e.getComment).getOrElse("")
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

  def fromMethod(symbol: String, method: JavaMethod): SymbolDocumentation = {
    new MetalsSymbolDocumentation(
      symbol,
      method.getName,
      toContent(method),
      "",
      typeParameters(symbol, method, method.getTypeParameters),
      parameters(symbol, method, method.getParameters)
    )
  }
  def fromClass(
      symbol: String,
      method: JavaClass
  ): SymbolDocumentation = {
    new MetalsSymbolDocumentation(
      symbol,
      method.getName,
      toContent(method),
      "",
      typeParameters(symbol, method, method.getTypeParameters),
      Nil.asJava
    )
  }
  def fromConstructor(
      symbol: String,
      method: JavaConstructor
  ): SymbolDocumentation = {
    new MetalsSymbolDocumentation(
      symbol,
      method.getName,
      toContent(method),
      "",
      typeParameters(symbol, method, method.getTypeParameters),
      parameters(symbol, method, method.getParameters)
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
  def typeParameters[D <: JavaGenericDeclaration](
      owner: String,
      method: JavaAnnotatedElement,
      tparams: util.List[JavaTypeVariable[D]]
  ): util.List[SymbolDocumentation] = {
    tparams.asScala.map { tparam =>
      val tparamName = s"<${tparam.getName}>"
      val docstring = method.getTagsByName("param").asScala.collectFirst {
        case tag if tag.getValue.startsWith(tparamName) =>
          tag.getValue
      }
      this.param(
        Symbols.Global(owner, Descriptor.TypeParameter(tparam.getName)),
        tparam.getName,
        docstring.getOrElse("")
      )
    }.asJava
  }
  def parameters(
      owner: String,
      method: JavaAnnotatedElement,
      params: util.List[JavaParameter]
  ): util.List[SymbolDocumentation] = {
    params.asScala.map { param =>
      val docstring = method.getTagsByName("param").asScala.collectFirst {
        case tag if tag.getValue.startsWith(param.getName) =>
          tag.getValue
      }
      this.param(
        Symbols.Global(owner, Descriptor.Parameter(param.getName)),
        param.getName,
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
