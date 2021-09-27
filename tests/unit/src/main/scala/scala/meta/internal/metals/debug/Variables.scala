package scala.meta.internal.metals.debug

import org.eclipse.lsp4j.{debug => dap}

final case class Variables(scopes: Map[String, List[Variable]]) {
  override def toString: String = {
    val serializedScopes = scopes.toList
      .sortBy(_._1)
      .map { case (scope, variables) =>
        s"$scope:" + variables.sortBy(_.name).mkString("(", ", ", ")")
      }

    serializedScopes.mkString("\n")
  }
}

final case class Variable(name: String, `type`: String, value: Variable.Value) {
  override def toString: String =
    value match {
      case Variable.MemoryReference => s"$name: ${`type`}"
      case Variable.Stringified(value) => s"$name: ${`type`} = $value"
    }
}

object Variable {
  sealed trait Value
  case object MemoryReference extends Value
  case class Stringified(override val toString: String) extends Value

  object Value {
    private val memoryReferencePattern = ".*@\\d+".r
    def apply(value: String): Value =
      value match {
        case memoryReferencePattern() => MemoryReference
        case _ => Stringified(value)
      }
  }

  def apply(v: dap.Variable): Variable = {
    Variable(v.getName, v.getType, Value(v.getValue))
  }
}
