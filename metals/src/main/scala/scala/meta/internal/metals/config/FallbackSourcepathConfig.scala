package scala.meta.internal.metals.config

sealed trait FallbackSourcepathConfig {
  def isAllSources: Boolean = false
  def isNone: Boolean = false
}

object FallbackSourcepathConfig {
  case object AllSources extends FallbackSourcepathConfig {
    override def isAllSources: Boolean = true
  }
  case object None extends FallbackSourcepathConfig {
    override def isNone: Boolean = true
  }
}
