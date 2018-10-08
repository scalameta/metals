package bench

import clouseau.Units
import org.openjdk.jol.info.GraphLayout
import scala.collection.mutable
import scala.meta.internal.mtags.InMemorySymbolIndex

object Memory {
  def printFootprint(iterable: sourcecode.Text[Object]): Unit = {
    val layout = GraphLayout.parseInstance(iterable.value)
    val size = layout.totalSize()
    pprint.log(iterable.source)
    pprint.log(Units.approx(size))
    val count: Int = iterable.value match {
      case it: Iterable[_] =>
        it.size
      case index: InMemorySymbolIndex =>
        val count = mutable.Set.empty[String]
        count ++= index.definitions.keys
        count ++= index.references.keys
        count.size
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
