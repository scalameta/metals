package scala.meta.internal.metals

import com.google.common.hash.BloomFilter
import java.text.DecimalFormat
import org.openjdk.jol.info.GraphLayout
import scala.collection.concurrent.TrieMap
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

  def footprint(iterable: sourcecode.Text[Object]): String = {
    footprint(iterable.source, iterable.value)
  }

  def format(n: Long): String =
    new DecimalFormat("#,###").format(n)

  def footprint(source: String, value: Object): String = {
    val layout = GraphLayout.parseInstance(value)
    val size = layout.totalSize()
    val suffix: String = value match {
      case index: OnDemandSymbolIndex =>
        val n = index.mtags.totalLinesOfScala
        s" (${format(n)} lines Scala)"
      case i: TrieMap[_, _] =>
        val elements = i.values.foldLeft(0L) {
          case (n, b: BloomFilter[_]) =>
            n + b.approximateElementCount()
          case (n, c: CompressedPackageIndex) =>
            n + c.bloom.approximateElementCount()
          case (n, _) =>
            n + 1
        }
        s" (${format(elements)} elements)"
      case _ =>
        ""
    }
    s"$source using ${approx(size)}$suffix"
  }

  def printFootprint(iterable: sourcecode.Text[Object]): Unit = {
    scribe.info(footprint(iterable).toString)
  }
}
