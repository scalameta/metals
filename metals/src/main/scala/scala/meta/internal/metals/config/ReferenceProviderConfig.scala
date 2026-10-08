package scala.meta.internal.metals.config

sealed trait ReferenceProviderConfig {
  def isBsp: Boolean = false
  def isMbt: Boolean = false
}

object ReferenceProviderConfig {
  case object BSP extends ReferenceProviderConfig {
    override def isBsp: Boolean = true
  }
  case object MBT extends ReferenceProviderConfig {
    override def isMbt: Boolean = true
  }
}
