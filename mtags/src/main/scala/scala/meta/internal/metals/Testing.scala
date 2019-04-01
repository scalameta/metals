package scala.meta.internal.metals

object Testing {
  def enable(): Unit = {
    System.setProperty("metals.testing", "true")
  }
  def isEnabled: Boolean = {
    "true" == System.getProperty("metals.testing")
  }
}
