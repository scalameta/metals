package bench

object MainBench {
  def main(args: Array[String]): Unit = {
    val bench = new ClasspathIndexingBench
    bench.setup()
    // bench.run()
  }
}
