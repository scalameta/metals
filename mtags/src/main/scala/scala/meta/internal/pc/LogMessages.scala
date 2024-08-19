package scala.meta.internal.pc

object LogMessages {
  def cancelled: String = "cancelled presentation compiler"
  def pluralName(name: String, count: Int): String =
    s"${count} ${name}${plural(count)}"
  def plural(count: Int): String =
    if (count == 1) ""
    else "s"
}
