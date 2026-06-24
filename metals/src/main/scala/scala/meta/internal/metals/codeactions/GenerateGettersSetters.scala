package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.parsing.InsertPoint
import scala.meta.internal.parsing.JavaClassInfo
import scala.meta.internal.parsing.JavaMemberKind
import scala.meta.internal.parsing.JavaTrees
import scala.meta.internal.parsing.JavaVariableInfo
import scala.meta.pc.CancelToken

import org.eclipse.{lsp4j => l}

class GenerateGettersSetters(
    javaTrees: JavaTrees,
    buffers: Buffers,
) extends CodeAction {

  override def kind: String = l.CodeActionKind.QuickFix

  override def contribute(
      params: l.CodeActionParams,
      token: CancelToken,
  )(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]] = Future {
    val path = params.getTextDocument().getUri().toAbsolutePath
    val position = params.getRange().getStart()
    val actions = for {
      text <- buffers.get(path).toSeq
      variable <- javaTrees.findEnclosingJavaVariable(path, position).toSeq
      if params.getRange().overlapsWith(variable.range.range)
      cls <- javaTrees.findEnclosingJavaClass(path, position).toSeq
      if isField(cls, variable)
    } yield {
      val insert = javaTrees.insertPointAfterFields(cls, text)
      val existingMethods = cls.members
        .filter(_.kind == JavaMemberKind.Method)
        .flatMap(_.name)
        .toSet

      def actionFor(
          title: String,
          newText: String,
      ): l.CodeAction =
        CodeActionBuilder.build(
          title = title,
          kind = kind,
          changes = Seq(path -> Seq(new l.TextEdit(insert.range, newText))),
        )

      val getter =
        if (
          existingMethods.contains(GenerateGettersSetters.getterName(variable))
        )
          None
        else
          Some(
            actionFor(
              GenerateGettersSetters.titleGetter(variable.name),
              GenerateGettersSetters.getterText(text, insert, variable),
            )
          )

      val setter =
        if (
          variable.isFinal ||
          existingMethods.contains(GenerateGettersSetters.setterName(variable))
        )
          None
        else
          Some(
            actionFor(
              GenerateGettersSetters.titleSetter(variable.name),
              GenerateGettersSetters
                .setterText(text, insert, cls.name, variable),
            )
          )

      (getter ++ setter).toSeq
    }
    actions.flatten
  }

  private def isField(
      cls: JavaClassInfo,
      variable: JavaVariableInfo,
  ): Boolean =
    cls.members.exists(member =>
      member.kind == JavaMemberKind.Field &&
        member.range.startOffset == variable.range.startOffset
    )

  override def isScala: Boolean = false

  override def isJava: Boolean = true

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

  /**
   * The getter name for a field. Only the primitive `boolean` uses the `is`
   * prefix.
   */
  def getterName(variable: JavaVariableInfo): String = {
    val prefix = if (variable.typ == "boolean") "is" else "get"
    prefix + variable.name.capitalize
  }

  def setterName(variable: JavaVariableInfo): String =
    "set" + variable.name.capitalize

  private def modifier(variable: JavaVariableInfo): String =
    if (variable.isStatic) "public static " else "public "

  def getterText(
      text: String,
      insert: InsertPoint,
      variable: JavaVariableInfo,
  ): String = {
    val unit = JavaMemberInsertion.indentUnit(text)
    JavaMemberInsertion.render(
      text,
      insert,
      Seq(
        s"${modifier(variable)}${variable.typ} ${getterName(variable)}() {",
        s"${unit}return ${variable.name};",
        "}",
      ),
    )
  }

  def setterText(
      text: String,
      insert: InsertPoint,
      className: String,
      variable: JavaVariableInfo,
  ): String = {
    val unit = JavaMemberInsertion.indentUnit(text)
    val target = if (variable.isStatic) className else "this"
    JavaMemberInsertion.render(
      text,
      insert,
      Seq(
        s"${modifier(variable)}void ${setterName(variable)}(${variable.typ} ${variable.name}) {",
        s"$unit$target.${variable.name} = ${variable.name};",
        "}",
      ),
    )
  }
}
