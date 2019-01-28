package bench

object MainBench {
  def main(args: Array[String]): Unit = {
    val bench = new OnDemandCompletionBench
    bench.setup()
    bench.completion = "memberDeep"
    val result = bench.complete()
//    result.getItems.asScala.foreach { item =>
//      pprint.log(item.getLabel)
//    }
  }
}
