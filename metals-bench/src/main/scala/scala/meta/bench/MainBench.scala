package scala.meta.bench
import scala.meta.bench.ClasspathSymbolsBench

object MainBench {
  def main(args: Array[String]): Unit = {
    val bench = new ClasspathSymbolsBench()
    bench.setup()
    bench.run()
    // bench.run()
  }
}
