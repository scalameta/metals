package scala.meta.internal.pc

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
    }
  }
}

case object Empty extends Descriptor {
  override val value: String = ""
}
final case class Package(value: String) extends Descriptor
final case class Method(value: String, disambiguator: String) extends Descriptor
final case class Class(value: String) extends Descriptor
final case class TypeVariable(value: String) extends Descriptor
final case class Var(value: String) extends Descriptor

object Descriptor {

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
