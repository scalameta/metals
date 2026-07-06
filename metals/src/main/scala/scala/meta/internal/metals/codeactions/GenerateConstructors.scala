package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.parsing.InsertPoint
import scala.meta.internal.parsing.JavaClass
import scala.meta.internal.parsing.JavaMethod
import scala.meta.internal.parsing.JavaTrees
import scala.meta.internal.parsing.JavaVariable
import scala.meta.io.AbsolutePath
import scala.meta.pc.CancelToken

import org.eclipse.{lsp4j => l}

class GenerateConstructors(
    javaTrees: JavaTrees,
    buffers: Buffers,
) extends CodeAction {
  import GenerateConstructors._

  override def kind: String = l.CodeActionKind.QuickFix

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
      val fields = constructorFields(cls)

      List(
        defaultConstructor(path, text, cls, insert),
        fieldsConstructor(path, text, cls, fields, unit, insert),
        copyConstructor(path, text, cls, fields, unit, insert),
      ).flatten
    }
    actions.flatten
  }

  override def isScala: Boolean = false

  override def isJava: Boolean = true

  private def build(
      path: AbsolutePath,
      text: String,
      insert: InsertPoint,
      title: String,
      memberLines: Seq[String],
  ): l.CodeAction = {
    val edit = new l.TextEdit(
      insert.range,
      JavaMemberInsertion.render(text, insert, memberLines),
    )
    CodeActionBuilder.build(
      title = title,
      kind = kind,
      changes = Seq(path -> Seq(edit)),
    )
  }

  private def defaultConstructor(
      path: AbsolutePath,
      text: String,
      cls: JavaClass,
      insert: InsertPoint,
  ): Option[l.CodeAction] =
    Option.when(!hasDefaultConstructor(cls) && !hasBlankFinalField(cls)) {
      build(
        path,
        text,
        insert,
        titleDefault(cls.name),
        Seq(
          s"${constructorModifier(cls)}${cls.name}() {",
          "}",
        ),
      )
    }

  private def fieldsConstructor(
      path: AbsolutePath,
      text: String,
      cls: JavaClass,
      fields: List[JavaVariable],
      unit: String,
      insert: InsertPoint,
  ): Option[l.CodeAction] =
    Option.when(fields.nonEmpty && !hasFieldsConstructor(cls, fields)) {
      val params =
        fields.map(field => s"${field.typ} ${field.name}").mkString(", ")
      build(
        path,
        text,
        insert,
        titleFromFields(cls.name),
        Seq(s"${constructorModifier(cls)}${cls.name}($params) {") ++
          fields.map(field => s"$unit${fieldAssignment(field)}") ++
          Seq("}"),
      )
    }

  private def copyConstructor(
      path: AbsolutePath,
      text: String,
      cls: JavaClass,
      fields: List[JavaVariable],
      unit: String,
      insert: InsertPoint,
  ): Option[l.CodeAction] =
    Option.when(fields.nonEmpty && !hasCopyConstructor(cls)) {
      build(
        path,
        text,
        insert,
        titleCopy(cls.name),
        Seq(
          s"${constructorModifier(cls)}${cls.name}(${copyConstructorType(cls)} other) {"
        ) ++
          fields.map(field => s"$unit${fieldAssignment(field, "other.")}") ++
          Seq("}"),
      )
    }

  private def fieldAssignment(
      field: JavaVariable,
      sourcePrefix: String = "",
  ): String =
    s"this.${field.name} = $sourcePrefix${field.name};"
}

object GenerateConstructors {
  def titleDefault(className: String): String =
    s"Generate default constructor for $className"
  def titleFromFields(className: String): String =
    s"Generate constructor from fields for $className"
  def titleCopy(className: String): String =
    s"Generate copy constructor for $className"

  private def constructorFields(cls: JavaClass): List[JavaVariable] =
    cls.members.collect {
      case field: JavaVariable if isConstructorField(field) => field
    }

  private def isConstructorField(field: JavaVariable): Boolean =
    !field.isStatic &&
      !(field.isFinal && field.hasInitializer) && JavaTrees.isValid(field.tree)

  private def hasDefaultConstructor(cls: JavaClass): Boolean =
    cls.members.exists {
      case m: JavaMethod => m.isConstructor && m.parameters.isEmpty
      case _ => false
    }

  private def hasBlankFinalField(cls: JavaClass): Boolean =
    cls.members.exists {
      case field: JavaVariable => field.isFinal && !field.hasInitializer
      case _ => false
    }

  private def hasFieldsConstructor(
      cls: JavaClass,
      fields: List[JavaVariable],
  ): Boolean =
    cls.members.exists {
      case method: JavaMethod if method.isConstructor =>
        equivalentConstructorSignature(
          method.parameters.map(_.typ),
          fields.map(_.typ),
        )
      case _ => false
    }

  private def hasCopyConstructor(cls: JavaClass): Boolean =
    cls.members.exists {
      case method: JavaMethod if method.isConstructor =>
        method.parameters match {
          case parameter :: Nil =>
            erasedType(parameter.typ) == erasedType(copyConstructorType(cls))
          case _ => false
        }
      case _ => false
    }

  private def copyConstructorType(cls: JavaClass): String = {
    if (cls.typeParameters.isEmpty) cls.name
    else
      cls.typeParameters.mkString(s"${cls.name}<", ", ", ">")
  }

  private def equivalentConstructorSignature(
      existingTypes: List[String],
      generatedTypes: List[String],
  ): Boolean =
    existingTypes.map(erasedType) == generatedTypes.map(erasedType)

  private def erasedType(typ: String): String = {
    typ.takeWhile(_ != '<').trim
  }

  private def constructorModifier(cls: JavaClass): String = {
    if (cls.isAbstract) "protected "
    else if (cls.isPublic) "public "
    else if (cls.isProtected) "protected "
    else if (cls.isPrivate) "private "
    else ""
  }
}
