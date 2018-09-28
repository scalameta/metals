package scala.meta.metals

import scala.util.Try
import io.circe.Encoder
import io.circe.Decoder
import io.circe.generic.extras.{ConfiguredJsonCodec => JsonCodec}
import io.circe.generic.extras.{Configuration => CirceConfiguration}
import io.circe.syntax._
import Configuration._
import java.nio.file.Path
import java.nio.file.Paths

@JsonCodec case class Configuration(
    sbt: Sbt = Sbt(),
    scalac: Scalac = Scalac(),
    scalafmt: Scalafmt = Scalafmt(),
    scalafix: Scalafix = Scalafix(),
    search: Search = Search(),
    hover: Enabled = Enabled(true),
    highlight: Enabled = Enabled(false),
    rename: Enabled = Enabled(false),
)

object Configuration {

  /** pretty-printed default configuration */
  lazy val defaultAsJson: String = Configuration().asJson.spaces2

  @JsonCodec case class Enabled(enabled: Boolean)

  @JsonCodec case class Sbt(
      diagnostics: Enabled = Enabled(true),
      command: String = "",
  )
  @JsonCodec case class Scalac(
      completions: Enabled = Enabled(false),
      diagnostics: Enabled = Enabled(false),
  ) {
    def enabled: Boolean = completions.enabled || diagnostics.enabled
  }

  @JsonCodec case class Scalafmt(
      enabled: Boolean = true,
      onSave: Boolean = false,
      version: String = "1.4.0",
      confPath: Option[Path] = Some(Scalafmt.defaultConfPath)
  )
  object Scalafmt {
    lazy val defaultConfPath = Paths.get(".scalafmt.conf")
  }
  @JsonCodec case class Scalafix(
      enabled: Boolean = true,
      confPath: Option[Path] = Some(Scalafix.defaultConfPath)
  )
  object Scalafix {
    lazy val defaultConfPath = Paths.get(".scalafix.conf")
  }
  @JsonCodec case class Search(
      indexJDK: Boolean = true,
      indexClasspath: Boolean = true
  )

  implicit val pathReads: Decoder[Path] =
    Decoder.decodeString.emapTry(s => Try(Paths.get(s)))
  implicit val pathWrites: Encoder[Path] =
    Encoder.encodeString.contramap(_.toString)
  implicit val circeConfiguration: CirceConfiguration =
    CirceConfiguration.default.withDefaults
}
