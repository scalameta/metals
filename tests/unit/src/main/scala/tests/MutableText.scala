package tests

import tests.MutableText.Insert
import scala.collection.mutable

object MutableText {
  def apply(text: String): MutableText = {
    val lines = mutable.LinkedHashMap[Int, String]()
    text.split('\n').foreach(line => lines += (lines.size -> line))
    new MutableText(lines)
  }

  case class Insert(line: Int, column: Int, value: String)
}

final class MutableText(lines: mutable.Map[Int, String]) {
  def updateWith(insertions: Seq[Insert]): Unit =
    for {
      // inserting at greater column first is easier - there is no need to adjust insertion offset
      Insert(line, column, value) <- insertions.sortBy(-_.column)
    } insert(line, column, value)

  private def insert(line: Int, column: Int, value: String): Unit = {
    val (prefix, suffix) = lines(line).splitAt(column)
    lines(line) = prefix + value + suffix
  }

  override def toString: String = {
    val builder = new mutable.StringBuilder()
    lines.foreach {
      case (_, line) =>
        builder.append(line)
        builder.append("\n")
    }

    builder
      .dropRight(1) // drop trailing new line
      .toString()
  }
}
