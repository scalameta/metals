package scala.meta.internal.metals

final case class SlowTaskConfig(value: String) {
  def isOff: Boolean = value == "off"
  def isOn: Boolean = value == "on"
  def isStatusBar: Boolean = value == "status-bar"
}

object SlowTaskConfig {
  def off = new SlowTaskConfig("off")
  def on = new SlowTaskConfig("on")
  def default = new SlowTaskConfig(
    System.getProperty("metals.slow-task", "off")
  )
}
