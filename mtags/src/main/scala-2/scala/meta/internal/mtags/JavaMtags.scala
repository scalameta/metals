package scala.meta.internal.mtags

import java.io.StringReader
import java.util.Comparator

import scala.meta.inputs.Input
import scala.meta.inputs.Position
import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.semanticdb.Language
import scala.meta.internal.semanticdb.SymbolInformation.Kind
import scala.meta.internal.semanticdb.SymbolInformation.Property

import com.thoughtworks.qdox._
import com.thoughtworks.qdox.model.JavaClass
import com.thoughtworks.qdox.model.JavaConstructor
import com.thoughtworks.qdox.model.JavaField
import com.thoughtworks.qdox.model.JavaMember
import com.thoughtworks.qdox.model.JavaMethod
import com.thoughtworks.qdox.model.JavaModel
import com.thoughtworks.qdox.parser.ParseException

object JavaMtags {
  def index(input: Input.VirtualFile): MtagsIndexer =
    new JavaMtags(input)
}
class JavaMtags(virtualFile: Input.VirtualFile) extends MtagsIndexer { self =>
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

  /**
   * Computes the start/end offsets from a name in a line number.
   *
   * Applies a simple heuristic to find the name: the first occurence of
   * name in that line. If the name does not appear in the line then
   * 0 is returned. If the name appears for example in the return type
   * of a method then we get the position of the return type, not the
   * end of the world.
   */
  def toRangePosition(line: Int, name: String): Position = {
    val offset = input.toOffset(line, 0)
    val columnAndLength = {
      val fromIndex = {
        // HACK(olafur) avoid hitting on substrings of "package".
        if (input.value.startsWith("package", offset)) "package".length
        else offset
      }
      val idx = input.value.indexOf(" " + name, fromIndex)
      if (idx == -1) (0, 0)
      else (idx - offset + " ".length, name.length)
    }
    input.toPosition(
      line,
      columnAndLength._1,
      line,
      columnAndLength._1 + columnAndLength._2
    )
  }

  def visitMembers[T <: JavaMember](fields: java.util.List[T]): Unit =
    if (fields == null) ()
    else fields.asScala.foreach(visitMember)

  def visitClasses(classes: java.util.List[JavaClass]): Unit =
    if (classes == null) ()
    else classes.asScala.foreach(visitClass)

  def visitClass(
      cls: JavaClass,
      name: String,
      pos: Position,
      kind: Kind,
      properties: Int
  ): Unit = {
    tpe(
      cls.getName,
      pos,
      kind,
      if (cls.isEnum) Property.ENUM.value else 0
    )
  }

  def visitClass(cls: JavaClass): Unit =
    withOwner(owner) {
      val kind = if (cls.isInterface) Kind.INTERFACE else Kind.CLASS
      val pos = toRangePosition(cls.lineNumber, cls.getName)
      visitClass(
        cls,
        cls.getName,
        pos,
        kind,
        if (cls.isEnum) Property.ENUM.value else 0
      )
      visitClasses(cls.getNestedClasses)
      visitMethods(cls)
      visitConstructors(cls)
      visitMembers(cls.getFields)
    }

  def visitConstructor(
      ctor: JavaConstructor,
      disambiguator: String,
      pos: Position,
      properties: Int
  ): Unit = {
    super.ctor(disambiguator, pos, 0)
  }

  def visitConstructors(cls: JavaClass): Unit = {
    val overloads = new OverloadDisambiguator()
    cls.getConstructors
      .iterator()
      .asScala
      .filterNot(_.isPrivate)
      .foreach { ctor =>
        val name = cls.getName
        val disambiguator = overloads.disambiguator(name)
        val pos = toRangePosition(ctor.lineNumber, name)
        withOwner() {
          visitConstructor(ctor, disambiguator, pos, 0)
        }
      }
  }

  def visitMethod(
      method: JavaMethod,
      name: String,
      disambiguator: String,
      pos: Position,
      properties: Int
  ): Unit = {
    super.method(name, disambiguator, pos, properties)
  }

  def visitMethods(cls: JavaClass): Unit = {
    val overloads = new OverloadDisambiguator()
    val methods = cls.getMethods
    methods.sort(new Comparator[JavaMethod] {
      override def compare(o1: JavaMethod, o2: JavaMethod): Int = {
        java.lang.Boolean.compare(o1.isStatic, o2.isStatic)
      }
    })
    methods.asScala.foreach { method =>
      val name = method.getName
      val disambiguator = overloads.disambiguator(name)
      val pos = toRangePosition(method.lineNumber, name)
      withOwner() {
        visitMethod(method, name, disambiguator, pos, 0)
      }
    }
  }

  def visitMember[T <: JavaMember](m: T): Unit =
    withOwner(owner) {
      val name = m.getName
      val line = m match {
        case c: JavaMethod => c.lineNumber
        case c: JavaField => c.lineNumber
        case _ => 0
      }
      val pos = toRangePosition(line, name)
      val kind: Kind = m match {
        case _: JavaMethod => Kind.METHOD
        case _: JavaField => Kind.FIELD
        case c: JavaClass =>
          if (c.isInterface) Kind.INTERFACE
          else Kind.CLASS
        case _ => Kind.UNKNOWN_KIND
      }
      term(name, pos, kind, 0)
    }

  implicit class XtensionJavaModel(m: JavaModel) {
    def lineNumber: Int = m.getLineNumber - 1
  }

}
