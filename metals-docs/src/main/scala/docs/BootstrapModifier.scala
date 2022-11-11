package docs

import scala.meta.inputs.Input

import mdoc.Reporter
import mdoc.StringModifier

class BootstrapModifier extends StringModifier {
  override val name: String = "bootstrap"
  override def process(
      info: String,
      code: Input,
      reporter: Reporter,
  ): String = {
    info.split(" ") match {
      case Array(binary, client) =>
        s"""
           |Next, build a `$binary` binary for the latest Metals release using the
           |[Coursier](https://github.com/coursier/coursier) command-line interface.
           |
           |${Docs.releasesResolverTable}
           |
           |```sh
           |# Make sure to use coursier v1.1.0-M9 or newer.
           |curl -L -o coursier https://git.io/coursier-cli
           |chmod +x coursier
           |./coursier bootstrap \\
           |  --java-opt -Xss4m \\
           |  --java-opt -Xms100m \\
           |  --java-opt -Dmetals.client=$client \\
           |  org.scalameta:metals_2.13:${Docs.release.version} \\
           |  -r sonatype:snapshots \\
           |  -o /usr/local/bin/$binary -f
           |```
           |Make sure the generated `$binary` binary is available on your `$$PATH`.
           |
           |You can check version of your binary by executing `$binary -version`.
           |
           |Configure the system properties `-Dhttps.proxyHost=… -Dhttps.proxyPort=…`
           |if you are behind an HTTP proxy.
           |""".stripMargin
      case _ =>
        reporter.error(s"Invalid info '$info'. Expected '<binary> <client>'")
        ""
    }
  }
}
