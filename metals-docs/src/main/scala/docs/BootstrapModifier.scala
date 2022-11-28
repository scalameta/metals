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
    s"""|```sh
        |coursier bootstrap org.scalameta:metals_2.13:${Docs.release.version} -o metals -f
        |```
        |
        |(optional) It's recommended to enable JVM string de-duplication and provide a
        |generous stack size and memory options.
        |
        |```sh
        |coursier bootstrap \\
        |  --java-opt -XX:+UseG1GC \\
        |  --java-opt -XX:+UseStringDeduplication  \\
        |  --java-opt -Xss4m \\
        |  --java-opt -Xms100m \\
        |  org.scalameta:metals_2.13:${Docs.release.version} -o metals -f
        |```""".stripMargin

  }
}
