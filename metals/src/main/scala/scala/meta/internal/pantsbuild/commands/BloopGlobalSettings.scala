package scala.meta.internal.pantsbuild.commands

import java.nio.file.Path
import java.nio.file.Paths

import scala.util.control.NonFatal

import scala.meta.io.AbsolutePath

import ujson.Obj

object BloopGlobalSettings {
  def update(workspace: AbsolutePath, newHome: Option[Path]): Boolean = {
    import scala.meta.internal.metals.MetalsEnrichments._
    val homedir = AbsolutePath(System.getProperty("user.home"))
    val file = homedir.resolve(".bloop").resolve("bloop.json")
    try {
      val text =
        if (file.isFile) file.readText
        else "{}"
      val oldJson: Obj = ujson.read(text).obj
      val oldHome: Option[Path] =
        oldJson.value.get("javaHome").map(_.str).map(Paths.get(_))
      val isHomeChanged = newHome.isDefined && newHome != oldHome
      if (isHomeChanged) {
        newHome.foreach { home => oldJson.value("javaHome") = home.toString() }
        val newJson = ujson.write(oldJson, indent = 4)
        file.writeText(newJson)
        scribe.info(s"bloop: updated global settings in $file")
      }
      isHomeChanged
    } catch {
      case NonFatal(e) =>
        scribe.error(s"bloop: failed to update global settings file $file", e)
        false
    }

  }
}
