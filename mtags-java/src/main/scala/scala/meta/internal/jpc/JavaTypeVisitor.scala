package scala.meta.internal.jpc

import javax.lang.model.`type`.ArrayType
import javax.lang.model.`type`.DeclaredType
import javax.lang.model.`type`.ErrorType
import javax.lang.model.`type`.ExecutableType
import javax.lang.model.`type`.IntersectionType
import javax.lang.model.`type`.NoType
import javax.lang.model.`type`.NullType
import javax.lang.model.`type`.PrimitiveType
import javax.lang.model.`type`.TypeMirror
import javax.lang.model.`type`.TypeVariable
import javax.lang.model.`type`.TypeVisitor
import javax.lang.model.`type`.UnionType
import javax.lang.model.`type`.UnknownTypeException
import javax.lang.model.`type`.WildcardType

import scala.jdk.CollectionConverters._

class JavaTypeVisitor extends TypeVisitor[String, Void] {

  override def visit(t: TypeMirror): String = visit(t, null)

  override def visit(t: TypeMirror, p: Void): String = t.accept(this, null)

  override def visitPrimitive(t: PrimitiveType, p: Void): String = t.toString

  override def visitNull(t: NullType, p: Void): String = t.toString

  override def visitArray(t: ArrayType, p: Void): String =
    s"${visit(t.getComponentType)}[]"

  override def visitDeclared(t: DeclaredType, p: Void): String = {
    val declaredType = t.asElement().toString

    val typeArguments = t.getTypeArguments
    val typeParameters =
      if (typeArguments.isEmpty) ""
      else s"<${typeArguments.asScala.map(visit).mkString(", ")}>"

    s"$declaredType$typeParameters"
  }

  override def visitError(t: ErrorType, p: Void): String = ""

  override def visitTypeVariable(t: TypeVariable, p: Void): String =
    t.asElement().toString

  override def visitWildcard(t: WildcardType, p: Void): String = {
    val superBound = t.getSuperBound
    val extendsBound = t.getExtendsBound

    val superBoundStr =
      if (superBound == null) "" else s" super ${visit(superBound)}"
    val extendsBoundStr =
      if (extendsBound == null) "" else s" extends ${visit(extendsBound)}"

    s"?$superBoundStr$extendsBoundStr"
  }

  override def visitExecutable(t: ExecutableType, p: Void): String = visit(
    t.getReturnType
  )

  override def visitNoType(t: NoType, p: Void): String = t.toString

  override def visitUnknown(t: TypeMirror, p: Void): String =
    throw new UnknownTypeException(t, p)

  override def visitUnion(t: UnionType, p: Void): String =
    t.getAlternatives.asScala.map(visit).mkString(" | ")

  override def visitIntersection(t: IntersectionType, p: Void): String =
    t.getBounds.asScala.map(visit).mkString(" & ")
}
