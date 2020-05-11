package scala.meta.internal.zipkin
import java.nio.file.Files
import java.util.Properties
import scala.meta.io.AbsolutePath
import scala.util.Try

case class Property private (metalsProperty: String) {

  val bloopProperty: String = metalsProperty.stripPrefix("metals.")

  def value(properties: Option[Properties]): Option[String] =
    properties.flatMap { props => Option(props.getProperty(metalsProperty)) }
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

  def booleanValue(
      prop: Property,
      properties: Option[Properties]
  ): Option[Boolean] =
    prop.value(properties).flatMap(str => Try(str.toBoolean).toOption)
}
