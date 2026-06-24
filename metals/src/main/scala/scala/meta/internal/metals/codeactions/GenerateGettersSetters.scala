package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.parsing.JavaClass
import scala.meta.internal.parsing.JavaMethod
import scala.meta.internal.parsing.JavaTrees
import scala.meta.internal.parsing.JavaVariable
import scala.meta.pc.CancelToken

import org.eclipse.{lsp4j => l}

class GenerateGettersSetters(
    javaTrees: JavaTrees,
    buffers: Buffers,
) extends CodeAction {
  import GenerateGettersSetters._

  override def kind: String = l.CodeActionKind.QuickFix
  override def isScala: Boolean = false
  override def isJava: Boolean = true

  override def contribute(
      params: l.CodeActionParams,
      token: CancelToken,
  )(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]] = Future {
    val path = params.getTextDocument().getUri().toAbsolutePath
    val range = params.getRange()
    val position = range.getStart()

    val actions = for {
      text <- buffers.get(path).toSeq
      cls <- javaTrees.findEnclosingJavaClass(path, position).toSeq
    } yield {
      val insert = JavaTrees.insertPointAfterFields(cls, text)
      val unit = JavaMemberInsertion.indentUnit(text)
      val existing = existingMethodNames(cls)

      def build(
          title: String,
          members: Seq[Seq[String]],
      ): Option[l.CodeAction] =
        Option.when(members.nonEmpty) {
          val edit = new l.TextEdit(
            insert.range,
            JavaMemberInsertion.renderAll(text, insert, members),
          )
          CodeActionBuilder.build(title, kind, changes = Seq(path -> Seq(edit)))
        }

      // cursor on the class name: getters/setters for all fields.
      if (range.overlapsWith(cls.nameRange.range)) {
        val fields = cls.members.collect { case field: JavaVariable => field }
        List(
          build(
            titleAllGetters(cls.name),
            fields.flatMap(getter(existing, unit, _)),
          ),
          build(
            titleAllSetters(cls.name),
            fields.flatMap(setter(existing, unit, cls.name, _)),
          ),
          build(
            titleAllGettersAndSetters(cls.name),
            fields.flatMap(field =>
              getter(existing, unit, field).toList ++
                setter(existing, unit, cls.name, field).toList
            ),
          ),
        ).flatten
      } else {
        // cursor on a field: getter/setter for that field.
        javaTrees
          .findEnclosingJavaVariable(path, position)
          .filter(field =>
            range
              .overlapsWith(field.nameRange.range) && isClassField(cls, field)
          )
          .map { field =>
            List(
              build(
                titleGetter(field.name),
                getter(existing, unit, field).toList,
              ),
              build(
                titleSetter(field.name),
                setter(existing, unit, cls.name, field).toList,
              ),
            ).flatten
          }
          .getOrElse(Nil)
      }
    }
    actions.flatten
  }

  private def isClassField(cls: JavaClass, field: JavaVariable): Boolean =
    cls.members.exists {
      case other: JavaVariable =>
        other.range.startOffset == field.range.startOffset
      case _ => false
    }
}

object GenerateGettersSetters {
  def titleGetter(fieldName: String): String =
    s"Generate getter for $fieldName"
  def titleSetter(fieldName: String): String =
    s"Generate setter for $fieldName"
  def titleAllGetters(className: String): String =
    s"Generate all getters for $className"
  def titleAllSetters(className: String): String =
    s"Generate all setters for $className"
  def titleAllGettersAndSetters(className: String): String =
    s"Generate all getters and setters for $className"

  private def existingMethodNames(cls: JavaClass): Set[String] =
    cls.members.collect {
      case method: JavaMethod if !method.isConstructor => method.name
    }.toSet

  private def getter(
      existing: Set[String],
      unit: String,
      field: JavaVariable,
  ): Option[Seq[String]] =
    Option.when(!existing.contains(getterName(field))) {
      Seq(
        s"${modifier(field)}${field.typ} ${getterName(field)}() {",
        s"${unit}return ${field.name};",
        "}",
      )
    }

  private def setter(
      existing: Set[String],
      unit: String,
      className: String,
      field: JavaVariable,
  ): Option[Seq[String]] =
    Option.when(!field.isFinal && !existing.contains(setterName(field))) {
      val target = if (field.isStatic) className else "this"
      Seq(
        s"${modifier(field)}void ${setterName(field)}(${field.typ} ${field.name}) {",
        s"$unit$target.${field.name} = ${field.name};",
        "}",
      )
    }

  private def getterName(field: JavaVariable): String =
    (if (field.typ == "boolean") "is" else "get") + field.name.capitalize

  private def setterName(field: JavaVariable): String =
    "set" + field.name.capitalize

  private def modifier(field: JavaVariable): String =
    if (field.isStatic) "public static " else "public "
}
