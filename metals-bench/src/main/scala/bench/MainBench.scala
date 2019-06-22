package bench

import scala.meta.internal.metals.Memory
import scala.meta.internal.metals.ClasspathSearch
import scala.meta.internal.metals.PackageIndex
import tests.Library
import java.nio.file.Files

object MainBench {
  def main(args: Array[String]): Unit = {
    val bench = new ClasspathIndexingBench
    bench.setup()
    val search =
      ClasspathSearch.fromClasspath(bench.classpath, _ => 1, bucketSize = 256)
    val classpath = Library.all.flatMap(_.classpath.entries) ++ PackageIndex.bootClasspath
    val size = classpath.foldLeft(0L) {
      case (a, f) =>
        a + Files.size(f.toNIO)
    }
    pprint.log(Memory.approx(size))
    pprint.log(Memory.footprint(search))
    // bench.run()
  }
}
