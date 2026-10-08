package scala.meta.internal.metals.config

sealed trait CompilerProgressConfig {
  def isEnabled: Boolean = false
  def isDisabled: Boolean = false
}

object CompilerProgressConfig {
  case object Enabled extends CompilerProgressConfig {
    override def isEnabled: Boolean = true
  }
  case object Disabled extends CompilerProgressConfig {
    override def isDisabled: Boolean = true
  }
}
