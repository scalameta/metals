package scala.meta.languageserver.ctags

import java.io.StringReader
import org.langmeta.inputs.Input
import com.thoughtworks.qdox._
import com.thoughtworks.qdox.model.JavaClass
import org.langmeta.languageserver.InputEnrichments._
import scala.meta.PACKAGE
import scala.meta.TRAIT
import scala.meta.OBJECT
import scala.meta.CLASS
import scala.meta.VAL
import scala.meta.VAR
import scala.meta.DEF
import com.thoughtworks.qdox.model.JavaField
import com.thoughtworks.qdox.model.JavaMember
import com.thoughtworks.qdox.model.JavaMethod
import com.thoughtworks.qdox.model.JavaModel
import com.thoughtworks.qdox.model.impl.DefaultJavaAnnotation
import com.thoughtworks.qdox.model.impl.DefaultJavaField
import com.thoughtworks.qdox.model.impl.DefaultJavaMethod
import org.langmeta.inputs.Position

object JavaCtags {
  private implicit class XtensionJavaModel(val m: JavaModel) extends AnyVal {
    def lineNumber: Int = m.getLineNumber - 1
  }
  def index(input: Input.VirtualFile): CtagsIndexer = {
    val builder = new JavaProjectBuilder()
    val source = builder.addSource(new StringReader(input.value))
    new CtagsIndexer { self =>
      override def indexRoot(): Unit = {

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
              // Simple tricks to avoid hitting on keywords.
              if (input.value.startsWith("package", offset))
                "package".length
              else if (input.value.startsWith("class", offset))
                "class".length
              else if (input.value.startsWith("interface", offset))
                "interface".length
              else if (input.value.startsWith("enum", offset))
                "enum".length
              else offset
            }
            val idx = input.value.indexOf(name, fromIndex)
            if (idx == -1) 0
            else idx - offset
          }
          val pos = input.toPosition(line, column, line, column + name.length)
          pos
        }

        /** (guess) returns if this is a default field
         *
         * I came across this example here
         * {{{
         * public interface Extension {
         *   Set<Extension> EMPTY_SET = new HashSet<Extension>();
         * }
         * }}}
         * from flexmark where EMPTY_SET is static but doesn't have isStatic = true.
         * This is a best guess at what's happening, but could be doing the
         * totally wrong thing.
         */
        def isDefaultField(m: JavaMember): Boolean = m match {
          case field: DefaultJavaField => true
          case _ => false
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
          withOwner(owner(m.isStatic || isDefaultField(m))) {
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
        if (source.getPackage != null) {
          source.getPackageName.split("\\.").foreach { p =>
            term(
              p,
              toRangePosition(source.getPackage.lineNumber, p),
              PACKAGE
            )
          }
        }
        source.getClasses.forEach(visitClass)
      }
      override def language: String = "Java"
    }
  }

}
