package scala.meta.internal.metals

case class JavaFormatterConfig(value: String) {
  def isEclipse: Boolean = value == "eclipse"
  def isGoogleJavaFormat: Boolean = value == "google-java-format"
  def isNone: Boolean = value == "none"
}

object JavaFormatterConfig {
  def fromString(value: String): Either[String, JavaFormatterConfig] =
    value match {
      case "eclipse" => Right(JavaFormatterConfig("eclipse"))
      case "google-java-format" =>
        Right(JavaFormatterConfig("google-java-format"))
      case "none" => Right(JavaFormatterConfig("none"))
      case _ =>
        Left(
          s"Invalid java formatter '$value'. Valid values are 'eclipse', 'google-java-format', and 'none'."
        )
    }
}
