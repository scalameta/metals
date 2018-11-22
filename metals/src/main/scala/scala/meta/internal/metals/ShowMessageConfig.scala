package scala.meta.internal.metals

final case class ShowMessageConfig(value: String) {
  def isOff: Boolean = value == "off"
  def isOn: Boolean = value == "on"
  def isLogMessage: Boolean = value == "log-message"
}

object ShowMessageConfig {
  def default = new ShowMessageConfig(
    System.getProperty("metals.show-message", "on")
  )
}
