package scala.meta.internal.pc

object LogMessages {
  def cancelled: String = "cancelled presentation compiler"
  def plural(count: Int): String =
    if (count == 1) ""
    else "s"
}
