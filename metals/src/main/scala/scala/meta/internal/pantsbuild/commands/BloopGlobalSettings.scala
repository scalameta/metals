package scala.meta.internal.pantsbuild.commands

import scala.meta.io.AbsolutePath
import scala.util.control.NonFatal
import ujson.Obj
import java.nio.file.Paths
import java.nio.file.Path
import scala.meta.internal.zipkin.ZipkinUrls
import ujson.Str

object BloopGlobalSettings {
  def update(newHome: Option[Path]): Boolean = {
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

      val properties = List(
        ZipkinUrls.zipkinServerUrl,
        BloopZipkinTraceProperties.localServiceName,
        BloopZipkinTraceProperties.traceStartAnnotation,
        BloopZipkinTraceProperties.traceEndAnnotation
      )
      val newOptions: List[String] = properties.foldLeft(oldOptions) {
        (options, prop) =>
          prop.value match {
            case Some(newValue) =>
              val oldValue = optionValue(options, prop.bloopProperty)
              if (!oldValue.contains(newValue)) {
                updateOption(options, prop.bloopProperty, newValue)
              } else {
                options
              }
            case None =>
              options
          }
      }
      val isHomeChanged = newHome.isDefined && newHome != oldHome
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

  case class Property(metalsProperty: String) {

    val bloopProperty: String = metalsProperty.stripPrefix("metals.")

    def value: Option[String] = Option(System.getProperty(metalsProperty))
  }

  private def optionValue(
      options: List[String],
      key: String
  ): Option[String] = {
    val regex = s"-D$key=(.*)".r
    options.collectFirst { case regex(value) => value.stripPrefix(s"-D$key=") }
  }

  private def updateOption(
      options: List[String],
      key: String,
      newValue: String
  ): List[String] = {
    val otherOptions = options.filterNot(_.startsWith(s"-D$key="))
    val newOption = s"-D$key=$newValue"
    newOption :: otherOptions
  }
}
