package docs

import java.nio.file.Paths

object Docs {
  def main(args: Array[String]): Unit = {
    val out = Paths.get("website", "target", "docs")
    // build arguments for mdoc
    val settings = mdoc
      .MainSettings()
      .withSiteVariables(
        Map(
          "VERSION" -> BuildInfo.version,
          "SCALA_VERSION" -> scala.util.Properties.versionNumberString
        )
      )
      .withOut(out)
      .withArgs(args.toList)
    // generate out/readme.md from working directory
    val exitCode = mdoc.Main.process(settings)
    // (optional) exit the main function with exit code 0 (success) or 1 (error)
    if (exitCode != 0) sys.exit(exitCode)
  }
}
