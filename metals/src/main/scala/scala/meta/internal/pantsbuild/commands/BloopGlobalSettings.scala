package scala.meta.internal.pantsbuild.commands

import scala.meta.internal.pantsbuild.PantsExport
import scala.meta.io.AbsolutePath
import scala.util.control.NonFatal
import ujson.Obj
import java.nio.file.Paths
import java.nio.file.Path
import ujson.Str

object BloopGlobalSettings {
  def update(export: PantsExport): Boolean = {
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
      val oldOptions: List[String] = oldJson.value
        .get("javaOptions")
        .map(_.arr.map(_.str).toList)
        .getOrElse(Nil)
      val newOptions: List[String] = ZipkinUrls.url match {
        case None => oldOptions
        case Some(url) =>
          val otherOptions =
            oldOptions.filterNot(_.startsWith("-Dzipkin.server.url")).toList
          s"-Dzipkin.server.url=$url" :: otherOptions
      }
      val newHome = export.jvmDistribution.javaHome
      val isHomeChanged = newHome != oldHome
      val isOptionsChanged = newOptions != oldOptions
      val isChanged = isHomeChanged || isOptionsChanged
      if (isChanged) {
        newHome.foreach { home => oldJson.value("javaHome") = home.toString() }
        oldJson.value("javaOptions") = newOptions.map(Str(_))
        val newJson = ujson.write(oldJson, indent = 4)
        file.writeText(newJson)
        scribe.info(s"bloop: updated global settings in $file")
      }
      isChanged
    } catch {
      case NonFatal(e) =>
        scribe.error(s"bloop: failed to update global settings file $file", e)
        false
    }

  }
}
