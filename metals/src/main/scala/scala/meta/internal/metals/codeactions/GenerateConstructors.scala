package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.parsing.InsertPoint
import scala.meta.internal.parsing.JavaClass
import scala.meta.internal.parsing.JavaMethod
import scala.meta.internal.parsing.JavaTrees
import scala.meta.pc.CancelToken

import org.eclipse.{lsp4j => l}

class GenerateDefaultConstructor(
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
    for {
      text <- buffers.get(path).toSeq
      cls <- javaTrees.findEnclosingJavaClass(path, position).toSeq
      if params.getRange().overlapsWith(cls.nameRange.range) &&
        !hasDefaultConstructor(cls)
    } yield {
      val insert = JavaTrees.insertPointAfterFields(cls, text)
      val edit = new l.TextEdit(
        insert.range,
        constructorText(text, cls.name, constructorModifier(cls), insert),
      )
      CodeActionBuilder.build(
        title = GenerateDefaultConstructor.title(cls.name),
        kind = kind,
        changes = Seq(path -> Seq(edit)),
      )
    }
  }

  override def isScala: Boolean = false

  override def isJava: Boolean = true

  private def hasDefaultConstructor(cls: JavaClass): Boolean =
    cls.members.exists {
      case m: JavaMethod => m.isConstructor && m.parameters.isEmpty
      case _ => false
    }

  private def constructorModifier(cls: JavaClass): String = {
    if (cls.isAbstract) "protected "
    else if (cls.isPublic) "public "
    else if (cls.isProtected) "protected "
    else if (cls.isPrivate) "private "
    else ""
  }

  private def constructorText(
      text: String,
      className: String,
      modifier: String,
      insert: InsertPoint,
  ): String =
    JavaMemberInsertion.render(
      text,
      insert,
      Seq(
        s"$modifier$className() {",
        "}",
      ),
    )
}

object GenerateDefaultConstructor {
  def title(className: String): String =
    s"Generate default constructor for $className"
}
