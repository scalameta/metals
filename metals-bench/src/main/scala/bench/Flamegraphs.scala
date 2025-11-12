package bench

import java.nio.file.Path
import java.nio.file.Paths
import java.util.regex.Pattern

import scala.collection.mutable.Buffer

import scala.meta.io.AbsolutePath

import one.convert.Arguments
import one.convert.JfrToFlame
import one.convert.JfrToHeatmap
import one.profiler.AsyncProfiler

case class JfrDir(dir: String) {
  def jfrProfile: Path = Paths.get(dir, "profile.jfr")
  def collapsed: AbsolutePath = AbsolutePath(
    Paths.get(dir, "profile.collapsed.txt")
  )
  def flamegraph: AbsolutePath = AbsolutePath(
    Paths.get(dir, "profile.flamegraph.html")
  )
  def heatmap: AbsolutePath = AbsolutePath(
    Paths.get(dir, "profile.heatmap.html")
  )
}
object JfrDir {
  lazy val fromProperties: Option[JfrDir] =
    Option(System.getProperty("metals.jfr.dir")).map(JfrDir)
}

object Flamegraphs {
  val profiler: AsyncProfiler = AsyncProfiler.getInstance()
  def setup(): Unit = {
    JfrDir.fromProperties.foreach { jfrDir =>
      val options = Buffer.empty[String]
      options += "start"
      options += "jfr"
      options += s"file=${jfrDir.jfrProfile}"
      val startResult = profiler.execute(options.mkString(","))
      if (startResult.trim() != "Profiling started") {
        scribe.info(s"started profiler: $startResult")
      }
    }
  }

  def tearDown(include: String = "", reverse: Boolean = false): Unit = {
    JfrDir.fromProperties.foreach { jfrDir =>
      val options = Buffer.empty[String]
      options += "stop"
      options += s"file=${jfrDir.jfrProfile}"
      val stopResult = profiler.execute(options.mkString(","))
      if (stopResult.isEmpty || stopResult == "OK") {
        // val collapsed = profiler.dumpCollapsed(Counter.SAMPLES)

        val args = new Arguments()
        if (include.nonEmpty) {
          args.include = Pattern.compile(include)
        }
        if (reverse) {
          args.reverse = true
        }
        args.output = "collapsed"
        JfrToFlame.convert(
          jfrDir.jfrProfile.toString(),
          jfrDir.collapsed.toString(),
          args,
        )
        args.output = "flamegraph"
        JfrToFlame.convert(
          jfrDir.jfrProfile.toString(),
          jfrDir.flamegraph.toString(),
          args,
        )
        args.output = "heatmap"
        JfrToHeatmap.convert(
          jfrDir.jfrProfile.toString(),
          jfrDir.heatmap.toString(),
          args,
        )
        scribe.info(s"jfr: ${jfrDir.jfrProfile}")
        scribe.info(s"collapsed: ${jfrDir.collapsed}")
        scribe.info(s"flamegraph: ${jfrDir.flamegraph}")
        scribe.info(s"heatmap: ${jfrDir.heatmap}")
        scribe.info(
          s"bun run --port 54123 ${jfrDir.flamegraph} ${jfrDir.heatmap}"
        )
        scribe.info("➜ http://localhost:54123/flamegraph")
        scribe.info("➜ http://localhost:54123/heatmap")
      } else {
        scribe.info(s"stopped profiler: $stopResult")
      }
    }
  }
}
