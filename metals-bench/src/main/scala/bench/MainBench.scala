package bench

import scala.meta.internal.metals.Time
import scala.meta.internal.metals.Timer

object MainBench {
  def main(args: Array[String]): Unit = {
    val bench = new ClasspathFuzzBench
    bench.setup()
    val symbols = bench.symbols
    1.to(10).foreach { i =>
      val timer = new Timer(Time.system)
      val result = symbols.search("File")
      if (i == 1) {
        pprint.log(result.map(_.getName))
      }
      pprint.log(result.length)
      scribe.info(s"time: $timer")
    }
  }
}
