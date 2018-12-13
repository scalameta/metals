package bench

import java.util.concurrent.TimeUnit
import scala.meta.internal.metals.Memory
import scala.meta.internal.metals.MetalsLogger
import scala.meta.internal.mtags.OnDemandSymbolIndex
import tests.Libraries

object MainBench {
  def main(args: Array[String]): Unit = {
    MetalsLogger.updateDefaultFormat()
    val classpath = Libraries.suite.map(_.sources()).reduce(_ ++ _)
    val start = System.nanoTime()
    val index = OnDemandSymbolIndex()
    classpath.entries.foreach(entry => index.addSourceJar(entry))
    val end = System.nanoTime()
    scribe.info(s"elapsed: ${TimeUnit.NANOSECONDS.toMillis(end - start)}ms")
    scribe.info(s"java lines: ${index.mtags.totalLinesOfJava}")
    scribe.info(s"scala lines: ${index.mtags.totalLinesOfScala}")
    Memory.printFootprint(index)
    val bench = new MetalsBench
    bench.scalacTokenize()
  }
}
