package scala.meta.languageserver.mtags

import java.io.StringReader
import scala.meta.CLASS
import scala.meta.DEF
import scala.meta.OBJECT
import scala.meta.PACKAGE
import scala.meta.TRAIT
import scala.meta.VAL
import scala.meta.VAR
import com.thoughtworks.qdox._
import com.thoughtworks.qdox.model.JavaClass
import com.thoughtworks.qdox.model.JavaField
import com.thoughtworks.qdox.model.JavaMember
import com.thoughtworks.qdox.model.JavaMethod
import com.thoughtworks.qdox.model.JavaModel
import com.thoughtworks.qdox.model.impl.DefaultJavaField
import org.langmeta.inputs.Input
import org.langmeta.inputs.Position
import org.langmeta.languageserver.InputEnrichments._

object JavaMtags {
  private implicit class XtensionJavaModel(val m: JavaModel) extends AnyVal {
    def lineNumber: Int = m.getLineNumber - 1
  }
  def index(input: Input.VirtualFile): MtagsIndexer = {
    val builder = new JavaProjectBuilder()
    new MtagsIndexer { self =>
      override def indexRoot(): Unit = {
        try {
          val source = builder.addSource(new StringReader(input.value))
          if (source.getPackage != null) {
            source.getPackageName.split("\\.").foreach { p =>
              term(p, toRangePosition(source.getPackage.lineNumber, p), PACKAGE)
            }
          }
          source.getClasses.forEach(visitClass)
        } catch {
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
        val column = {
          val fromIndex = {
            // HACK(olafur) avoid hitting on substrings of "package".
            if (input.value.startsWith("package", offset)) "package".length
            else offset
          }
          val idx = input.value.indexOf(name, fromIndex)
          if (idx == -1) 0
          else idx - offset
        }
        val pos = input.toPosition(line, column, line, column + name.length)
        pos
      }

      def visitFields[T <: JavaMember](fields: java.util.List[T]): Unit =
        if (fields == null) ()
        else fields.forEach(visitMember)

      def visitClasses(classes: java.util.List[JavaClass]): Unit =
        if (classes == null) ()
        else classes.forEach(visitClass)

      def visitClass(cls: JavaClass): Unit =
        withOwner(owner(cls.isStatic)) {
          val flags = if (cls.isInterface) TRAIT else CLASS
          val pos = toRangePosition(cls.lineNumber, cls.getName)
          if (cls.isEnum) {
            term(cls.getName, pos, OBJECT)
          } else {
            withOwner() { term(cls.getName, pos, OBJECT) } // object
            tpe(cls.getName, pos, flags)
          }
          visitClasses(cls.getNestedClasses)
          visitFields(cls.getMethods)
          visitFields(cls.getFields)
          visitFields(cls.getEnumConstants)
        }

      def visitMember[T <: JavaMember](m: T): Unit =
        withOwner(owner(m.isStatic)) {
          val name = m.getName
          val line = m match {
            case c: JavaMethod => c.lineNumber
            case c: JavaField => c.lineNumber
            // TODO(olafur) handle constructos
            case _ => 0
          }
          val pos = toRangePosition(line, name)
          val flags: Long = m match {
            case c: JavaMethod => DEF
            case c: JavaField =>
              if (c.isFinal || c.isEnumConstant) VAL
              else VAR
            // TODO(olafur) handle constructos
            case _ => 0L
          }
          term(name, pos, flags)
        }
      override def language: String = "Java"
    }
  }

}
