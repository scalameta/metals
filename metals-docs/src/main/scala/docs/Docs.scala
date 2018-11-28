package docs

import java.nio.file.Paths
import scala.meta.internal.metals.{BuildInfo => V}

object Docs {
  def main(args: Array[String]): Unit = {
    val out = Paths.get("website", "target", "docs")
    val settings = mdoc
      .MainSettings()
      .withSiteVariables(
        Map(
          "VERSION" -> V.metalsVersion,
          "BLOOP_VERSION" -> V.bloopVersion,
          "SCALAMETA_VERSION" -> V.scalametaVersion,
          "SCALA211_VERSION" -> V.scala211,
          "SCALA_VERSION" -> V.scala212
        )
      )
      .withOut(out)
      .withArgs(args.toList)
    val exitCode = mdoc.Main.process(settings)
    if (exitCode != 0) {
      sys.exit(exitCode)
    }
  }
}
