package scala.meta.internal.metals

import java.text.DecimalFormat
import org.openjdk.jol.info.GraphLayout
import scala.meta.internal.mtags.OnDemandSymbolIndex

object Memory {
  // Adapted from https://github.com/non/clouseau
  val si: List[String] = List("B", "K", "M", "G", "T", "P", "E", "Z", "Y")

  def approx(bytes: Long): String = {
    def loop(value: Double, units: List[String]): String =
      if (value < 1024.0) "%.3g%s".format(value, units.head)
      else loop(value / 1024.0, units.tail)
    loop(bytes.toDouble, si)
  }

  case class Footprint(
      name: String,
      total: String,
      linesScala: Option[Long]
  ) {
    override def toString: String = {
      val suffix = linesScala.fold("") { lines =>
        s" (${new DecimalFormat("#,###").format(lines)} lines Scala)"
      }
      s"$name using $total$suffix"
    }
  }

  def footprint(iterable: sourcecode.Text[Object]): Footprint = {
    val layout = GraphLayout.parseInstance(iterable.value)
    val size = layout.totalSize()
    val linesOfCode: Option[Long] = iterable.value match {
      case index: OnDemandSymbolIndex =>
        Some(index.mtags.totalLinesOfScala)
      case _ =>
        None
    }
    Footprint(iterable.source, approx(size), linesOfCode)
  }

  def printFootprint(iterable: sourcecode.Text[Object]): Unit = {
    scribe.info(footprint(iterable).toString)
  }
}
