package scala.meta.internal.pantsbuild.commands

import metaconfig.cli.CliApp
import fansi.Str
import fansi.Color

object MetaconfigEnrichments {
  implicit class XtensionCliApp(app: CliApp) {
    def warn(message: Str): Unit = {
      app.out.println(Color.LightYellow("warn: ") ++ message)
    }
    def info(message: Str): Unit = {
      app.out.println(Color.LightBlue("info: ") ++ message)
    }
  }
}
