package scala.meta.internal.metals

class CodeBuilder {
  val sb = new StringBuilder
  def println(line: String): this.type = {
    if (line.nonEmpty) {
      sb.append(line).append("\n")
    }
    this
  }
  override def toString: String = sb.toString()
}
