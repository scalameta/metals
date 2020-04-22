package scala.meta.internal.zipkin
import java.nio.file.Files
import java.util.Properties

import scala.util.Try

import scala.meta.io.AbsolutePath

case class Property private (metalsProperty: String) {

  val bloopProperty: String = metalsProperty.stripPrefix("metals.")

  def value(properties: Option[Properties]): Option[String] =
    properties.map(_.getProperty(metalsProperty))

  def updateOptions(
      properties: Option[Properties]
  )(options: List[String]): List[String] = {
    value(properties) match {
      case Some(newValue) =>
        val oldValue = readValue(options)
        if (!oldValue.contains(newValue)) {
          val otherOptions =
            options.filterNot(_.startsWith(s"-D$bloopProperty="))
          val newOption = s"-D$bloopProperty=$newValue"
          newOption :: otherOptions
        } else {
          options
        }
      case None =>
        options
    }
  }

  def readValue(options: List[String]): Option[String] = {
    val regex = s"-D$bloopProperty=(.*)".r
    options.collectFirst {
      case regex(value) => value.stripPrefix(s"-D$bloopProperty=")
    }
  }
}
object Property {

  def fromFile(workspace: AbsolutePath): Option[Properties] = {
    Try(
      Files.newInputStream(
        workspace.resolve("fastpass").resolve("fastpass.properties").toNIO
      )
    ).map { in =>
      val prop = new Properties
      prop.load(in)
      prop
    }.toOption
  }
}
