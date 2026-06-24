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
import scala.meta.io.AbsolutePath
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
    val range = params.getRange()
    val position = range.getStart()

    val fieldActions = for {
      text <- buffers.get(path).toSeq
      variable <- javaTrees.findEnclosingJavaVariable(path, position).toSeq
      if range.overlapsWith(variable.range.range)
      cls <- javaTrees.findEnclosingJavaClass(path, position).toSeq
      if isField(cls, variable)
    } yield fieldLevelActions(path, text, cls, variable)

    val classActions = for {
      text <- buffers.get(path).toSeq
      cls <- javaTrees.findEnclosingJavaClass(path, position).toSeq
      if range.overlapsWith(cls.nameRange)
    } yield classLevelActions(path, text, cls)

    fieldActions.flatten ++ classActions.flatten
  }

  /** Getter/setter for the single field under the cursor. */
  private def fieldLevelActions(
      path: AbsolutePath,
      text: String,
      cls: JavaClassInfo,
      variable: JavaVariableInfo,
  ): Seq[l.CodeAction] = {
    import GenerateGettersSetters._
    val insert = JavaTrees.insertPointAfterFields(cls, text)
    val unit = JavaMemberInsertion.indentUnit(text)
    val existing = existingMethodNames(cls)

    val getter =
      if (existing.contains(getterName(variable))) None
      else
        Some(
          action(
            path,
            titleGetter(variable.name),
            insert,
            JavaMemberInsertion.renderAll(
              text,
              insert,
              Seq(getterLines(unit, variable)),
            ),
          )
        )

    val setter =
      if (variable.isFinal || existing.contains(setterName(variable))) None
      else
        Some(
          action(
            path,
            titleSetter(variable.name),
            insert,
            JavaMemberInsertion.renderAll(
              text,
              insert,
              Seq(setterLines(unit, cls.name, variable)),
            ),
          )
        )

    (getter ++ setter).toSeq
  }

  /** "Generate all ..." actions offered when the cursor is on the class name. */
  private def classLevelActions(
      path: AbsolutePath,
      text: String,
      cls: JavaClassInfo,
  ): Seq[l.CodeAction] = {
    import GenerateGettersSetters._
    val fields = cls.members.flatMap(_.field)
    val existing = existingMethodNames(cls)
    val unit = JavaMemberInsertion.indentUnit(text)
    val insert = JavaTrees.insertPointAfterFields(cls, text)

    val needGetters = fields.filter(f => !existing.contains(getterName(f)))
    val needSetters =
      fields.filter(f => !f.isFinal && !existing.contains(setterName(f)))

    def allAction(
        title: String,
        members: Seq[Seq[String]],
    ): Option[l.CodeAction] =
      if (members.isEmpty) None
      else
        Some(
          action(
            path,
            title,
            insert,
            JavaMemberInsertion.renderAll(text, insert, members),
          )
        )

    val getters =
      allAction(
        titleAllGetters(cls.name),
        needGetters.map(getterLines(unit, _)),
      )
    val setters =
      allAction(
        titleAllSetters(cls.name),
        needSetters.map(setterLines(unit, cls.name, _)),
      )
    // Getter then setter, grouped per field.
    val both = allAction(
      titleAllGettersAndSetters(cls.name),
      fields.flatMap { f =>
        val g =
          if (existing.contains(getterName(f))) Nil
          else Seq(getterLines(unit, f))
        val s =
          if (f.isFinal || existing.contains(setterName(f))) Nil
          else Seq(setterLines(unit, cls.name, f))
        g ++ s
      },
    )

    (getters ++ setters ++ both).toSeq
  }

  private def action(
      path: AbsolutePath,
      title: String,
      insert: InsertPoint,
      newText: String,
  ): l.CodeAction =
    CodeActionBuilder.build(
      title = title,
      kind = kind,
      changes = Seq(path -> Seq(new l.TextEdit(insert.range, newText))),
    )

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

  def existingMethodNames(cls: JavaClassInfo): Set[String] =
    cls.members.filter(_.kind == JavaMemberKind.Method).flatMap(_.name).toSet

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

  def getterLines(unit: String, variable: JavaVariableInfo): Seq[String] =
    Seq(
      s"${modifier(variable)}${variable.typ} ${getterName(variable)}() {",
      s"${unit}return ${variable.name};",
      "}",
    )

  def setterLines(
      unit: String,
      className: String,
      variable: JavaVariableInfo,
  ): Seq[String] = {
    val target = if (variable.isStatic) className else "this"
    Seq(
      s"${modifier(variable)}void ${setterName(variable)}(${variable.typ} ${variable.name}) {",
      s"$unit$target.${variable.name} = ${variable.name};",
      "}",
    )
  }
}
