package docs

import java.nio.file.Paths
import scala.meta.internal.metals.{BuildInfo => V}

object Docs {
  def main(args: Array[String]): Unit = {
    val out = Paths.get("website", "target", "docs")
    // build arguments for mdoc
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
      .withStringModifiers(
        List(
          new RequirementsModifier
        )
      )
      .withArgs(args.toList)
    // generate out/readme.md from working directory
    val exitCode = mdoc.Main.process(settings)
    // (optional) exit the main function with exit code 0 (success) or 1 (error)
    if (exitCode != 0) sys.exit(exitCode)
  }
}
