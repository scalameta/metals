package scala.meta.internal.jpc

sealed trait Descriptor {
  import Descriptor._

  def value: String
  override def toString: String = {
    this match {
      case Empty => ""
      case Package(value) => s"${encode(value)}/"
      case Method(value, disambiguator) => s"${encode(value)}$disambiguator."
      case Class(value) => s"${encode(value)}#"
      case TypeVariable(value) => s"[${encode(value)}]"
      case Var(value) => s"${encode(value)}."
      case Parameter(value) => s"(${encode(value)})"
      case Local(value) => s"local${value}"
    }
  }
}

case object Empty extends Descriptor {
  override val value: String = ""
}
final case class Package(value: String) extends Descriptor
final case class Method(value: String, disambiguator: String) extends Descriptor
final case class Parameter(value: String) extends Descriptor
final case class Class(value: String) extends Descriptor
final case class TypeVariable(value: String) extends Descriptor
final case class Var(value: String) extends Descriptor
final case class Local(value: String) extends Descriptor

object Descriptor {
  def isGlobal(value: String): Boolean = {
    value != "" && !value.startsWith("local")
  }

  def encode(value: String): String =
    if (value == "") ""
    else {
      val (start, parts) = (value.head, value.tail)
      val isStartOk = Character.isJavaIdentifierStart(start)
      val isPartsOk = parts.forall(Character.isJavaIdentifierPart)
      if (isStartOk && isPartsOk) value
      else "`" + value + "`"
    }
}
