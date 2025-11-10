package scala.meta.internal.metals

import java.text.DecimalFormat

import scala.collection.concurrent.TrieMap
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import scala.meta.internal.mtags.OnDemandSymbolIndex

import com.google.common.hash.BloomFilter
import org.openjdk.jol.info.GraphLayout

object Memory {
  // Adapted from https://github.com/non/clouseau
  private val si: List[String] =
    List("B", "K", "M", "G", "T", "P", "E", "Z", "Y")

  def approx(bytes: Long): String = {
    def loop(value: Double, units: List[String]): String =
      if (value < 1024.0) "%.3g%s".format(value, units.head)
      else loop(value / 1024.0, units.tail)
    loop(bytes.toDouble, si)
  }

  private def format(n: Long): String =
    new DecimalFormat("#,###").format(n)

  def footprint(source: String, value: Object): Option[String] = Try {
    val layout = GraphLayout.parseInstance(value)
    val size = layout.totalSize()
    val suffix: String = value match {
      case index: OnDemandSymbolIndex =>
        val n = index.mtags().totalLinesOfScala
        s" (${format(n)} lines Scala)"
      case i: TrieMap[_, _] =>
        val elements = i.values.foldLeft(0L) {
          case (n, b: BloomFilter[_]) =>
            n + b.approximateElementCount()
          case (n, i: IdentifierIndex.IndexEntry) =>
            n + i.bloom.approximateElementCount()
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
  } match {
    case Failure(throwable) =>
      if (throwable.getMessage.contains(jolMagicFieldError))
        scribe.error(errorMsg(source))
      else
        scribe.error(throwable)
      None
    case Success(value) =>
      Some(value)
  }

  /**
   * Log memory footprint of given values.
   * Due to https://github.com/openjdk/jol/commit/5dafe85a1fca52342cb965e673513b76768ab945
   * short circuit whenever error is encountered to not flood user with exceptions.
   *
   * @param objects list of tuples (object name, object itself)
   */
  def logMemory(objects: List[(String, Object)]): Unit =
    objects.headOption
      .flatMap { case (source, value) =>
        footprint(source, value)
      } match {
      case Some(footprint) =>
        scribe.info(s"memory: $footprint")
        logMemory(objects.tail)
      case None =>
        ()
    }

  private def errorMsg(source: String): String =
    s"org.openjdk.jol cannot compute memory footprint for $source. Try to run Metals server with -Djol.magicFieldOffset=true property See https://github.com/openjdk/jol/commit/5dafe85a1fca52342cb965e673513b76768ab945 for more details."

  private final val jolMagicFieldError: String =
    "try with -Djol.magicFieldOffset=true"

}
