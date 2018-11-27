package docs

import mdoc.Reporter
import mdoc.StringModifier
import scala.meta.inputs.Input
import scala.meta.internal.metals.BuildInfo

class BootstrapModifier extends StringModifier {
  override val name: String = "bootstrap"

  override def process(
      info: String,
      code: Input,
      reporter: Reporter
  ): String = {
    info.split(" ") match {
      case Array(binary, client) =>
        s"""
           |Next, build a `$binary` binary using the
           |[Coursier](https://github.com/coursier/coursier) (v1.1+) command-line interface.
           |
           |```sh
           |curl -L -o coursier https://git.io/coursier &&
           |    chmod +x coursier &&
           |./coursier bootstrap \\
           |  --java-opt -XX:+UseG1GC \\
           |  --java-opt -XX:+UseStringDeduplication  \\
           |  --java-opt -Xss4m \\
           |  --java-opt -Xms1G \\
           |  --java-opt -Xmx4G  \\
           |  --java-opt -Dmetals.client=$client \\
           |  org.scalameta:metals_2.12:${BuildInfo.metalsVersion} \\
           |  -r bintray:scalacenter/releases \\
           |  -o $binary -f
           |```
           |
           |(optional) Feel free to place this binary anywhere on your `$$PATH`, for example
           |adapt the `-o` flag like this:
           |
           |```diff
           |-  -o $binary -f
           |+  -o /usr/local/bin/$binary -f
           |```
           |""".stripMargin
      case _ =>
        reporter.error(s"Invalid info '$info'. Expected '<binary> <client>'")
        ""
    }
  }
}
