package scala.meta.internal.metals

final case class DoctorFormatConfig(value: String) {
  def isHtml: Boolean = value == "html"
  def isJson: Boolean = value == "json"
  def isMarkdown: Boolean = value == "markdown"
}

object DoctorFormatConfig {
  def html = new DoctorFormatConfig("html")
  def json = new DoctorFormatConfig("json")
  def markdown = new DoctorFormatConfig("markdown")
  def default = new DoctorFormatConfig(
    System.getProperty("metals.doctor-format", "html")
  )
}
