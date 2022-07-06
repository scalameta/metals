package scala.meta.internal.mtags

import java.io.StringReader

import scala.meta.inputs.Input
import scala.meta.inputs.Position
import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.semanticdb.Language
import scala.meta.internal.semanticdb.SymbolInformation.Kind
import scala.meta.internal.semanticdb.SymbolInformation.Property

import com.thoughtworks.qdox._
import com.thoughtworks.qdox.model.JavaClass
import com.thoughtworks.qdox.model.JavaConstructor
import com.thoughtworks.qdox.model.JavaModel
import com.thoughtworks.qdox.parser.ParseException

object JavaTopLevels {
  def index(input: Input.VirtualFile): MtagsIndexer = {
    new JavaMtags(input)
  }
}
class JavaTopLevels(virtualFile: Input.VirtualFile) extends JavaMtagsIndexer {
  self =>
  val builder = new JavaProjectBuilder()
  override def language: Language = Language.JAVA

  override def input: Input.VirtualFile = virtualFile

  override def indexRoot(): Unit = {
    try {
      val source = builder.addSource(new StringReader(input.value))
      if (source.getPackage != null) {
        source.getPackageName.split("\\.").foreach { p =>
          pkg(
            p,
            toRangePosition(source.getPackage.lineNumber, p)
          )
        }
      }
      source.getClasses.asScala.foreach(visitClass)
    } catch {
      case _: ParseException | _: NullPointerException =>
      // Parse errors are ignored because the Java source files we process
      // are not written by the user so there is nothing they can do about it.
    }
  }

  def visitClass(cls: JavaClass): Unit =
    withOwner(owner) {
      val kind = if (cls.isInterface) Kind.INTERFACE else Kind.CLASS
      val pos = toRangePosition(cls.lineNumber, cls.getName)
      tpe(
        cls.getName,
        pos,
        kind,
        if (cls.isEnum) Property.ENUM.value else 0
      )
    }

  def visitConstructor(
      ctor: JavaConstructor,
      disambiguator: String,
      pos: Position,
      properties: Int
  ): Unit = {
    super.ctor(disambiguator, pos, properties)
  }

  implicit class XtensionJavaModel(m: JavaModel) {
    def lineNumber: Int = m.getLineNumber - 1
  }

}
