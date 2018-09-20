package scala.meta.metals

import org.langmeta.AbsolutePath
import org.langmeta.RelativePath
import scala.util.Try
import io.circe.Encoder
import io.circe.Decoder
import io.circe.generic.extras.{ConfiguredJsonCodec => JsonCodec}
import io.circe.generic.extras.{Configuration => CirceConfiguration}
import io.circe.syntax._

import Configuration._

@JsonCodec case class Configuration(
    sbt: Sbt = Sbt(),
    bsp: Bsp = Bsp(),
    scalac: Scalac = Scalac(),
    scalafmt: Scalafmt = Scalafmt(),
    scalafix: Scalafix = Scalafix(),
    search: Search = Search(),
    hover: Enabled = Enabled(true),
    highlight: Enabled = Enabled(false),
    rename: Enabled = Enabled(false),
)

object Configuration {
  implicit val circeConfiguration: CirceConfiguration =
    CirceConfiguration.default.withDefaults

  /** pretty-printed default configuration */
  lazy val defaultAsJson: String = Configuration().asJson.spaces2

  @JsonCodec case class Enabled(enabled: Boolean)

  @JsonCodec case class Sbt(
      diagnostics: Enabled = Enabled(true),
      command: String = "",
  )

  @JsonCodec case class Bsp(
      enabled: Enabled = Enabled(true),
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
      confPath: Option[RelativePath] = Some(Scalafmt.defaultConfPath)
  )
  object Scalafmt {
    lazy val defaultConfPath = RelativePath(".scalafmt.conf")
  }
  @JsonCodec case class Scalafix(
      enabled: Boolean = true,
      confPath: Option[RelativePath] = Some(Scalafix.defaultConfPath)
  )
  object Scalafix {
    lazy val defaultConfPath = RelativePath(".scalafix.conf")
  }
  @JsonCodec case class Search(
      indexJDK: Boolean = true,
      indexClasspath: Boolean = true
  )

  implicit val absolutePathReads: Decoder[AbsolutePath] =
    Decoder.decodeString.emapTry(s => Try(AbsolutePath(s)))
  implicit val absolutePathWrites: Encoder[AbsolutePath] =
    Encoder.encodeString.contramap(_.toString)
  implicit val relativePathReads: Decoder[RelativePath] =
    Decoder.decodeString.emapTry(s => Try(RelativePath(s)))
  implicit val relativePathWrites: Encoder[RelativePath] =
    Encoder.encodeString.contramap(_.toString)

}
