package scala.meta.internal.metals.config

sealed trait DefinitionProviderConfig {
  def isMBT(javaSymbolLoader: JavaSymbolLoaderConfig): Boolean =
    javaSymbolLoader.isTurbineClasspath
  def isProtobuf: Boolean = false
}

object DefinitionProviderConfig {
  case object MBT extends DefinitionProviderConfig {
    override def isMBT(javaSymbolLoader: JavaSymbolLoaderConfig): Boolean = true
  }
  case object Protobuf extends DefinitionProviderConfig {
    override def isProtobuf: Boolean = true
  }

  case object All extends DefinitionProviderConfig {
    override def isMBT(javaSymbolLoader: JavaSymbolLoaderConfig): Boolean = true
    override def isProtobuf: Boolean = true
  }
}
