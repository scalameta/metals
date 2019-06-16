package bench

object MainBench {
  def main(args: Array[String]): Unit = {
    val bench = new ClasspathSymbolsBench()
    bench.setup()
    bench.run()
    // bench.run()
  }
}
