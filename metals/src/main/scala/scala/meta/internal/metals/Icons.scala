package scala.meta.internal.metals

abstract class Icons {
  def rocket: String
  def sync: String
  def alert: String
  def info: String
  def check: String
  final def all: List[String] = List(
    rocket,
    sync,
    alert,
    info,
    check
  )
}
object Icons {
  def translate(from: Icons, to: Icons, message: String): String = {
    from.all.zip(to.all).collectFirst {
      case (a, b) if message.startsWith(a) =>
        b + message.stripPrefix(a)
    }
  }.getOrElse(message)
  def default: Icons = {
    System.getProperty("metals.icons") match {
      case "octicons" | "vscode" => vscode
      case "unicode" => unicode
      case "atom" => atom
      case _ => none
    }
  }
  case object unicode extends Icons {
    override def rocket: String = "🚀 "
    override def sync: String = "🔄 "
    override def alert: String = "⚠️ "
    override def info: String = "ℹ️ "
    override def check: String = "✅ "
  }
  case object none extends Icons {
    override def rocket: String = ""
    override def sync: String = ""
    override def alert: String = ""
    override def info: String = ""
    override def check: String = ""
  }
  case object vscode extends Icons {
    override def rocket: String = "$(rocket) "
    override def sync: String = "$(sync) "
    override def alert: String = "$(alert) "
    override def info: String = "$(info) "
    override def check: String = "$(check) "
  }
  case object atom extends Icons {
    private def span(id: String) = s"<span class='icon icon-$id'></span> "
    override def rocket: String = span("rocket")
    override def sync: String = span("sync")
    override def alert: String = span("alert")
    override def info: String = span("info")
    override def check: String = span("check")
  }
}
