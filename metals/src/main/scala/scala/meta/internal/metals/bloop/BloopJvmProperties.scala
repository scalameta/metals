package scala.meta.internal.metals.bloop

sealed trait BloopJvmProperties {
  def properties: Option[List[String]]
}

object BloopJvmProperties {
  case object Uninitialized extends BloopJvmProperties {
    def properties: Option[List[String]] = None
  }
  case object Empty extends BloopJvmProperties {
    def properties: Option[List[String]] = None
  }
  case class WithProperties(props: List[String]) extends BloopJvmProperties {
    def properties: Option[List[String]] = Some(props)
  }
}
