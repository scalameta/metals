package scala.meta.metals

import org.langmeta.AbsolutePath
import org.langmeta.RelativePath
import scala.util.Try
import io.circe.Encoder
import io.circe.Decoder
import io.circe.generic.extras.{ConfiguredJsonCodec => JsonCodec}
import io.circe.generic.extras.{Configuration => CirceConfiguration}

import Configuration._

@JsonCodec case class Configuration(
    sbt: Sbt = Sbt(),
    scalac: Scalac = Scalac(),
    scalafmt: Scalafmt = Scalafmt(),
    scalafix: Scalafix = Scalafix(),
    search: Search = Search(),
    hover: Hover = Hover(),
    highlight: Highlight = Highlight(),
    rename: Rename = Rename(),
)

object Configuration {
  implicit val circeConfiguration: CirceConfiguration =
    CirceConfiguration.default.withDefaults

  @JsonCodec case class Sbt(
      enabled: Boolean = false,
      command: String = "test:compile"
  )
  @JsonCodec case class Scalac(
      completions: Completions = Completions(enabled = false),
      diagnostics: Diagnostics = Diagnostics(enabled = false),
  ) {
    def enabled: Boolean = completions.enabled || diagnostics.enabled
  }
  @JsonCodec case class Completions(enabled: Boolean = false)
  @JsonCodec case class Diagnostics(enabled: Boolean = false)

  @JsonCodec case class Hover(enabled: Boolean = true)
  @JsonCodec case class Highlight(enabled: Boolean = false)
  @JsonCodec case class Rename(enabled: Boolean = false)

  @JsonCodec case class Scalafmt(
      enabled: Boolean = true,
      onSave: Boolean = false,
      version: String = "1.3.0",
      confPath: Option[RelativePath] = None
  )
  object Scalafmt {
    lazy val defaultConfPath = RelativePath(".scalafmt.conf")
  }
  @JsonCodec case class Scalafix(
      enabled: Boolean = true,
      confPath: Option[RelativePath] = None
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
