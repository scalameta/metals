package bench

object MainBench {
  def main(args: Array[String]): Unit = {
    val bench = new CachedSearchAndCompilerCompletionBench
    bench.setup()
    bench.completion = args.headOption.getOrElse("scopeDeep")
    val result = bench.complete()
    result.getItems.forEach { item =>
      pprint.log(item.getLabel)
    }
  }
}
