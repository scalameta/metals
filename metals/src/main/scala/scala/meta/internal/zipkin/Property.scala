package scala.meta.internal.zipkin

case class Property(metalsProperty: String) {

  val bloopProperty: String = metalsProperty.stripPrefix("metals.")

  def value: Option[String] = Option(System.getProperty(metalsProperty))

  def updateOptions(options: List[String]): List[String] = {
    value match {
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
