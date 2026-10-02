package scala.meta.internal.metals.config

sealed trait FallbackClasspathConfig {
  def isAll3rdparty: Boolean = false
  def isGuessed: Boolean = false
  def isMbt: Boolean = false
}

object FallbackClasspathConfig {

  case object Default extends FallbackClasspathConfig {
    override def isAll3rdparty: Boolean = true
    override def isMbt: Boolean = true
  }
  case object All3rdparty extends FallbackClasspathConfig {
    override def isAll3rdparty: Boolean = true
  }
  case object Guessed extends FallbackClasspathConfig {
    override def isGuessed: Boolean = true
  }
  case object Mbt extends FallbackClasspathConfig {
    override def isMbt: Boolean = true
  }
  case object None extends FallbackClasspathConfig
}
