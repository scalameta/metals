package bench

import clouseau.Units
import org.openjdk.jol.info.GraphLayout
import scala.meta.internal.mtags.OnDemandSymbolIndex

object Memory {
  def printFootprint(iterable: sourcecode.Text[Object]): Unit = {
    val layout = GraphLayout.parseInstance(iterable.value)
    val size = layout.totalSize()
    pprint.log(iterable.source)
    pprint.log(Units.approx(size))
    val count: Long = iterable.value match {
      case it: Iterable[_] =>
        it.size
      case index: OnDemandSymbolIndex =>
        // 100k loc
        index.mtags.totalLinesOfCode / 100000
      case _ =>
        1
    }
    if (count > 1) {
      val elementSize = size / count
      pprint.log(count)
      pprint.log(Units.approx(elementSize))
    }
  }
}
