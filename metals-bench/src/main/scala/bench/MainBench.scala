package bench

object MainBench {
  def main(args: Array[String]): Unit = {
    val bench = new CachedSearchAndCompilerCompletionBench
    bench.setup()
    bench.completion = args.headOption.getOrElse("scopeDeep")
    val result = bench.complete()
    if (result.getItems.isEmpty) pprint.log(result)
    result.getItems.forEach { item =>
      pprint.log(item.getLabel)
    }
  }
}
