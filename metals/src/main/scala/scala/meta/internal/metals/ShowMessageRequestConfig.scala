package scala.meta.internal.metals

final case class ShowMessageRequestConfig(value: String) {
  def isOff: Boolean = value == "off"
  def isOn: Boolean = value == "on"
  def isLogMessage: Boolean = value == "log-message"
}

object ShowMessageRequestConfig {
  def on = new ShowMessageRequestConfig("on")
  def off = new ShowMessageRequestConfig("off")
  def logMessage = new ShowMessageRequestConfig("log-message")
  def default = new ShowMessageRequestConfig(
    System.getProperty("metals.show-message-request", "on")
  )
}
