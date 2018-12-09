package docs

import mdoc.Reporter
import mdoc.StringModifier
import scala.meta.inputs.Input

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
           |Next, build a `$binary` binary for the latest Metals release using the
           |[Coursier](https://github.com/coursier/coursier) command-line interface.
           |
           || Version                   | Published             | Resolver                |
           || ---                       | ---                   | ---                     |
           ||  ${Docs.release.version}  | ${Docs.release.date}  | `-r sonatype:releases`  |
           ||  ${Docs.snapshot.version} | ${Docs.snapshot.date} | `-r sonatype:snapshots` |
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
           |  org.scalameta:metals_2.12:${Docs.release.version} \\
           |  -r bintray:scalacenter/releases \\
           |  -r sonatype:snapshots \\
           |  -o /usr/local/bin/$binary -f
           |```
           |Make sure the generated `$binary` binary is available on your `$$PATH`.
           |""".stripMargin
      case _ =>
        reporter.error(s"Invalid info '$info'. Expected '<binary> <client>'")
        ""
    }
  }
}
