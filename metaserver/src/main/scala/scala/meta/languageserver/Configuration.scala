package scala.meta.languageserver

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
    rename: Rename = Rename(),
)

object Configuration {
  implicit val circeConfiguration: CirceConfiguration =
    CirceConfiguration.default.withDefaults

  @JsonCodec case class Sbt(
      enabled: Boolean = false,
      command: String = "test:compile"
  )
  @JsonCodec case class Scalac(enabled: Boolean = false)
  @JsonCodec case class Hover(enabled: Boolean = false)
  @JsonCodec case class Rename(enabled: Boolean = false)

  @JsonCodec case class Scalafmt(
      enabled: Boolean = true,
      onSave: Boolean = true,
      version: String = "1.3.0",
      confPath: Option[RelativePath] = None
  )
  object Scalafmt {
    lazy val defaultConfPath = RelativePath(".scalafmt.conf")
  }
  @JsonCodec case class Scalafix(
      enabled: Boolean = true,
      confPath: RelativePath = RelativePath(".scalafix.conf")
  )
  // TODO(olafur): re-enable indexJDK after https://github.com/scalameta/language-server/issues/43 is fixed
  @JsonCodec case class Search(
      indexJDK: Boolean = false,
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
