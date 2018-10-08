package scala.meta.internal.mtags

import com.thoughtworks.qdox._
import com.thoughtworks.qdox.model.JavaClass
import com.thoughtworks.qdox.model.JavaField
import com.thoughtworks.qdox.model.JavaMember
import com.thoughtworks.qdox.model.JavaMethod
import com.thoughtworks.qdox.model.JavaModel
import com.thoughtworks.qdox.parser.ParseException
import java.io.StringReader
import java.util.Comparator
import scala.collection.mutable
import scala.meta.inputs.Input
import scala.meta.inputs.Position
import scala.meta.internal.semanticdb.Language
import scala.meta.internal.semanticdb.SymbolInformation.Kind
import scala.meta.internal.semanticdb.SymbolInformation.Property
import scala.meta.internal.mtags.Enrichments._

/**
 * Utility to generate method symbol  disambiguators according to SemanticDB spec.
 *
 * See https://scalameta.org/docs/semanticdb/specification.html#scala-symbol
 */
final class OverloadDisambiguator(
    names: mutable.Map[String, Int] = mutable.Map.empty
) {
  def disambiguator(name: String): String = {
    val n = names.getOrElseUpdate(name, 0)
    names(name) = n + 1
    if (n == 0) "()"
    else s"(+$n)"
  }
}

object JavaMtags {
  private implicit class XtensionJavaModel(val m: JavaModel) extends AnyVal {
    def lineNumber: Int = m.getLineNumber - 1
  }

  def index(input: Input.VirtualFile): MtagsIndexer = {
    val builder = new JavaProjectBuilder()
    new MtagsIndexer { self =>
      override def language: Language = Language.JAVA
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
          source.getClasses.forEach(visitClass)
        } catch {
          case _: ParseException =>
          case _: NullPointerException => ()
          // Hitting on this fellow here indexing the JDK
          // Error indexing file:///Library/Java/JavaVirtualMachines/jdk1.8.0_102.jdk/Contents/Home/src.zip/java/time/temporal/IsoFields.java
          // java.lang.NullPointerException: null
          // at com.thoughtworks.qdox.builder.impl.ModelBuilder.createTypeVariable(ModelBuilder.java:503)
          // at com.thoughtworks.qdox.builder.impl.ModelBuilder.endMethod(ModelBuilder.java:470)
          // TODO(olafur) report bug to qdox.
        }
      }

      /** Computes the start/end offsets from a name in a line number.
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
        else fields.forEach(visitMember)

      def visitClasses(classes: java.util.List[JavaClass]): Unit =
        if (classes == null) ()
        else classes.forEach(visitClass)

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
          visitClasses(cls.getNestedClasses)
          visitMethods(cls)
          visitConstructors(cls)
          visitMembers(cls.getFields)
        }

      def visitConstructors(cls: JavaClass): Unit = {
        val overloads = new OverloadDisambiguator()
        cls.getConstructors.forEach { ctor =>
          val name = cls.getName
          val disambiguator = overloads.disambiguator(name)
          val pos = toRangePosition(ctor.lineNumber, name)
          withOwner() {
            super.ctor(disambiguator, pos, 0)
          }
        }
      }
      def visitMethods(cls: JavaClass): Unit = {
        val overloads = new OverloadDisambiguator()
        val methods = cls.getMethods
        methods.sort(new Comparator[JavaMethod] {
          override def compare(o1: JavaMethod, o2: JavaMethod): Int = {
            java.lang.Boolean.compare(o1.isStatic, o2.isStatic)
          }
        })
        methods.forEach { method =>
          val name = method.getName
          val disambiguator = overloads.disambiguator(name)
          val pos = toRangePosition(method.lineNumber, name)
          withOwner() {
            super.method(name, disambiguator, pos, 0)
          }
        }
      }
      def visitMember[T <: JavaMember](m: T): Unit =
        withOwner(owner) {
          val name = m.getName
          val line = m match {
            case c: JavaMethod => c.lineNumber
            case c: JavaField => c.lineNumber
            // TODO(olafur) handle constructos
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
    }
  }

}
