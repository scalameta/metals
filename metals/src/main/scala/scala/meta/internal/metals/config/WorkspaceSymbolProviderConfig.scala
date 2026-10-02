package scala.meta.internal.metals.config

sealed trait WorkspaceSymbolProviderConfig {
  def isMBT: Boolean

  override def toString(): String =
    this.getClass().getSimpleName().toLowerCase().stripSuffix("$")
}
object WorkspaceSymbolProviderConfig {
  case object MBT extends WorkspaceSymbolProviderConfig {
    override def isMBT: Boolean = true
  }
  case object BSP extends WorkspaceSymbolProviderConfig {
    override def isMBT: Boolean = false
  }
}
