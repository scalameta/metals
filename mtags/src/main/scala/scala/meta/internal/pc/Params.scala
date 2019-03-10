package scala.meta.internal.pc

case class Params(
    labels: Seq[String],
    kind: Params.Kind
)

object Params {
  sealed abstract class Kind
  case object TypeParameterKind extends Kind
  case object NormalKind extends Kind
  case object ImplicitKind extends Kind
}
