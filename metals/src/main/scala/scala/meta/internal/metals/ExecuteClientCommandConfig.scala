package scala.meta.internal.metals

final case class ExecuteClientCommandConfig(value: String) {
  def isOff: Boolean = value == "off"
  def isOn: Boolean = value == "on"
}
object ExecuteClientCommandConfig {
  def on = new ExecuteClientCommandConfig("on")
  def off = new ExecuteClientCommandConfig("off")
  def default =
    new ExecuteClientCommandConfig(
      System.getProperty("metals.execute-client-command", "off")
    )
}
