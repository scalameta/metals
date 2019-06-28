package tests

case class PrettyPrintTree(
    value: String,
    children: List[PrettyPrintTree] = Nil
) {
  def isEmpty = value.isEmpty() && children.isEmpty
  def render(indent: String, sb: StringBuilder): Unit = {
    if (!isEmpty) {
      sb.append(indent)
        .append(value)
        .append("\n")
      val newIndent = indent + "  "
      children.foreach { child =>
        child.render(newIndent, sb)
      }
    }
  }
  override def toString(): String = {
    val out = new StringBuilder()
    render("", out)
    out.toString()
  }
}

object PrettyPrintTree {
  def empty = PrettyPrintTree("")
}
