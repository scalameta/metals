package bench

import java.util.concurrent.TimeUnit
import scala.meta.metals.MetalsLogger
import scala.meta.internal.mtags.InMemorySymbolIndex
import tests.Libraries

object MainBench {
  def main(args: Array[String]): Unit = {
    MetalsLogger.updateFormat()
    val classpath = Libraries.suite.map(_.sources()).reduce(_ ++ _)
    val start = System.nanoTime()
    val index = InMemorySymbolIndex()
    classpath.entries.foreach(entry => index.addSourceJar(entry))
    val end = System.nanoTime()
    scribe.info(s"elapsed: ${TimeUnit.NANOSECONDS.toMillis(end - start)}ms")
    Memory.printFootprint(index)
  }
}
