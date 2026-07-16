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

class GenerateEqualsHashCodeToString(
    javaTrees: JavaTrees,
    buffers: Buffers,
) extends CodeAction {
  import GenerateEqualsHashCodeToString._

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
      if range.overlapsWith(cls.nameRange.range)
    } yield {
      val insert = JavaTrees.insertPointAfterFields(cls, text)
      val unit = JavaMemberInsertion.indentUnit(text)
      val fields = instanceFields(cls)
      val members =
        List(
          equalsMethod(cls, fields, unit),
          hashCodeMethod(cls, fields, unit),
          toStringMethod(cls, fields, unit),
        ).flatten

      Option.when(members.nonEmpty) {
        val edit = new l.TextEdit(
          insert.range,
          JavaMemberInsertion.renderAll(text, insert, members),
        )
        CodeActionBuilder.build(
          title(cls.name),
          kind,
          changes = Seq(path -> Seq(edit)),
        )
      }
    }

    actions.flatten
  }

  private def equalsMethod(
      cls: JavaClass,
      fields: List[JavaVariable],
      unit: String,
  ): Option[Seq[String]] =
    Option.when(!hasEquals(cls)) {
      val fieldChecks = fields.map(equalsExpression)
      val result =
        if (fieldChecks.isEmpty) "true" else fieldChecks.mkString(" && ")

      Seq(
        "@Override",
        "public boolean equals(Object obj) {",
        s"${unit}if (this == obj) {",
        s"${unit}${unit}return true;",
        s"${unit}}",
        s"${unit}if (obj == null || getClass() != obj.getClass()) {",
        s"${unit}${unit}return false;",
        s"${unit}}",
      ) ++
        Option
          .when(fields.nonEmpty)(
            s"${unit}${equalsCastType(cls)} that = (${equalsCastType(cls)}) obj;"
          )
          .toSeq ++
        Seq(
          s"${unit}return $result;",
          "}",
        )
    }

  private def hashCodeMethod(
      cls: JavaClass,
      fields: List[JavaVariable],
      unit: String,
  ): Option[Seq[String]] =
    Option.when(!hasHashCode(cls)) {
      Seq(
        "@Override",
        "public int hashCode() {",
        s"${unit}return java.util.Objects.hash(${fields.map(hashCodeExpression).mkString(", ")});",
        "}",
      )
    }

  private def toStringMethod(
      cls: JavaClass,
      fields: List[JavaVariable],
      unit: String,
  ): Option[Seq[String]] =
    Option.when(!hasToString(cls)) {
      val resultLines =
        if (fields.isEmpty) Seq(s"""${unit}return "${cls.name}{}";""")
        else {
          val fieldLines = fields.zipWithIndex.map { case (field, index) =>
            val prefix = if (index == 0) "" else ", "
            s"""${unit}${unit}"$prefix${field.name}=" + ${toStringExpression(field)} +"""
          }

          Seq(s"""${unit}return "${cls.name}{" +""") ++
            fieldLines ++
            Seq(s"""${unit}${unit}"}";""")
        }

      Seq(
        "@Override",
        "public String toString() {",
      ) ++ resultLines ++ Seq("}")
    }
}

object GenerateEqualsHashCodeToString {
  def title(className: String): String =
    s"Generate equals(), hashCode() and toString() for $className"

  private def instanceFields(cls: JavaClass): List[JavaVariable] =
    cls.members.collect {
      case field: JavaVariable
          if !field.isStatic && JavaTrees.isValid(field.tree) =>
        field
    }

  private def hasEquals(cls: JavaClass): Boolean =
    cls.members.exists {
      case method: JavaMethod =>
        method.name == "equals" &&
        method.parameters.map(_.typ) == List("Object")
      case _ => false
    }

  private def hasHashCode(cls: JavaClass): Boolean =
    cls.members.exists {
      case method: JavaMethod =>
        method.name == "hashCode" && method.parameters.isEmpty
      case _ => false
    }

  private def hasToString(cls: JavaClass): Boolean =
    cls.members.exists {
      case method: JavaMethod =>
        method.name == "toString" && method.parameters.isEmpty
      case _ => false
    }

  private def equalsCastType(cls: JavaClass): String =
    if (cls.typeParameters.isEmpty) cls.name
    else cls.typeParameters.map(_ => "?").mkString(s"${cls.name}<", ", ", ">")

  private def equalsExpression(field: JavaVariable): String =
    if (isNestedArray(field))
      s"java.util.Arrays.deepEquals(this.${field.name}, that.${field.name})"
    else if (isArray(field))
      s"java.util.Arrays.equals(this.${field.name}, that.${field.name})"
    else s"java.util.Objects.equals(this.${field.name}, that.${field.name})"

  private def hashCodeExpression(field: JavaVariable): String =
    if (isNestedArray(field)) s"java.util.Arrays.deepHashCode(${field.name})"
    else if (isArray(field)) s"java.util.Arrays.hashCode(${field.name})"
    else field.name

  private def toStringExpression(field: JavaVariable): String =
    if (isNestedArray(field)) s"java.util.Arrays.deepToString(${field.name})"
    else if (isArray(field)) s"java.util.Arrays.toString(${field.name})"
    else field.name

  private def isArray(field: JavaVariable): Boolean =
    arrayDimensions(field) > 0

  private def isNestedArray(field: JavaVariable): Boolean =
    arrayDimensions(field) > 1

  private def arrayDimensions(field: JavaVariable): Int =
    field.typ.sliding(2).count(_ == "[]")
}
