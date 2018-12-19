package docs

import com.google.gson.GsonBuilder
import mdoc.Reporter
import mdoc.StringModifier
import scala.meta.inputs.Input
import scala.meta.internal.io.PathIO
import scala.meta.internal.metals.Configs.GlobSyntaxConfig
import scala.meta.io.AbsolutePath

class FileWatcherModifier extends StringModifier {
  override val name: String = "file-watcher"

  override def process(
      info: String,
      code: Input,
      reporter: Reporter
  ): String = {
    val workspace = PathIO.workingDirectory.toNIO.getRoot
      .resolve("to")
      .resolve("workspace")
    val options =
      GlobSyntaxConfig.default.registrationOptions(AbsolutePath(workspace))
    val json = new GsonBuilder().setPrettyPrinting().create().toJson(options)
    s"""
       |```json
       |$json
       |```
     """.stripMargin
  }
}
