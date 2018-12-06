package docs

import mdoc.Reporter
import mdoc.StringModifier
import scala.meta.inputs.Input

class BootstrapModifier extends StringModifier {
  override val name: String = "bootstrap"
  val snapshot = Snapshot.latest()
  override def process(
      info: String,
      code: Input,
      reporter: Reporter
  ): String = {
    info.split(" ") match {
      case Array(binary, client) =>
        s"""
           |Next, build a `$binary` binary using the
           |[Coursier](https://github.com/coursier/coursier) command-line interface.
           |
           |```sh
           |# Make sure to use coursier v1.1.0-M9 or newer.
           |curl -L -o coursier https://git.io/coursier
           |chmod +x coursier
           |./coursier bootstrap \\
           |  --java-opt -XX:+UseG1GC \\
           |  --java-opt -XX:+UseStringDeduplication  \\
           |  --java-opt -Xss4m \\
           |  --java-opt -Xms1G \\
           |  --java-opt -Xmx4G  \\
           |  --java-opt -Dmetals.client=$client \\
           |  org.scalameta:metals_2.12:${snapshot.version} \\
           |  -r bintray:scalacenter/releases \\
           |  -r sonatype:snapshots \\
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
