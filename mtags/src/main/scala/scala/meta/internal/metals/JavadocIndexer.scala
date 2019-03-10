package scala.meta.internal.metals

import com.thoughtworks.qdox.model.JavaAnnotatedElement
import com.thoughtworks.qdox.model.JavaClass
import com.thoughtworks.qdox.model.JavaConstructor
import com.thoughtworks.qdox.model.JavaGenericDeclaration
import com.thoughtworks.qdox.model.JavaMethod
import com.thoughtworks.qdox.model.JavaParameter
import com.thoughtworks.qdox.model.JavaTypeVariable
import java.util
import scala.collection.JavaConverters._
import scala.meta.inputs.Input
import scala.meta.inputs.Position
import scala.meta.internal.docstrings.MarkdownGenerator
import scala.meta.internal.mtags.JavaMtags
import scala.meta.internal.semanticdb.Scala.Descriptor
import scala.meta.internal.semanticdb.Scala.Symbols
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.pc.SymbolDocumentation
import scala.util.control.NonFatal

/**
 * Extracts Javadoc from Java source code.
 */
class JavadocIndexer(
    input: Input.VirtualFile,
    fn: SymbolDocumentation => Unit
) extends JavaMtags(input) {
  override def visitClass(
      cls: JavaClass,
      name: String,
      pos: Position,
      kind: SymbolInformation.Kind,
      properties: Int
  ): Unit = {
    super.visitClass(cls, name, pos, kind, properties)
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

  def markdown(e: JavaAnnotatedElement): String = {
    try MarkdownGenerator.fromDocstring(s"/**${e.getComment}\n*/", Map.empty)
    catch {
      case NonFatal(_) =>
        // The Scaladoc parser implementation uses fragile regexp processing which
        // sometimes causes exceptions.
        e.getComment
    }
  }
  def fromMethod(symbol: String, method: JavaMethod): SymbolDocumentation = {
    new MetalsSymbolDocumentation(
      symbol,
      method.getName,
      markdown(method),
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
      markdown(method),
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
      markdown(method),
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
  def all(input: Input.VirtualFile): List[SymbolDocumentation] = {
    val buf = List.newBuilder[SymbolDocumentation]
    foreach(input)(buf += _)
    buf.result()
  }
  def foreach(
      input: Input.VirtualFile
  )(fn: SymbolDocumentation => Unit): Unit = {
    new JavadocIndexer(input, fn).indexRoot()
  }
}
