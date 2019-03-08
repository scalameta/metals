package scala.meta.internal.metals

import com.thoughtworks.qdox.model.JavaClass
import com.thoughtworks.qdox.model.JavaConstructor
import com.thoughtworks.qdox.model.JavaMethod
import scala.meta.inputs.Input
import scala.meta.inputs.Position
import scala.meta.internal.mtags.JavaMtags
import scala.meta.internal.semanticdb.Scala.Descriptor
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.pc.SymbolDocumentation

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
    fn(
      MetalsSymbolDocumentation.fromClass(currentOwner, cls)
    )
  }
  override def visitConstructor(
      ctor: JavaConstructor,
      disambiguator: String,
      pos: Position,
      properties: Int
  ): Unit = {
    fn(
      MetalsSymbolDocumentation.fromConstructor(
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
      MetalsSymbolDocumentation.fromMethod(
        symbol(Descriptor.Method(name, disambiguator)),
        method
      )
    )
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
