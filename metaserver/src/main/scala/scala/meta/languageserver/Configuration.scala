package scala.meta.languageserver

import play.api.libs.json._
import play.api.libs.functional.syntax._

import org.langmeta.AbsolutePath
import org.langmeta.RelativePath
import java.nio.file.Paths

import Configuration._
import play.api.libs.json.OFormat

case class Configuration(
    scalac: Scalac = Scalac(),
    scalafmt: Scalafmt = Scalafmt(),
    scalafix: Scalafix = Scalafix(),
    search: Search = Search(),
    hover: Hover = Hover(),
    rename: Rename = Rename(),
)

object Configuration {

  case class Scalac(enabled: Boolean = false)
  case class Hover(enabled: Boolean = false)
  case class Rename(enabled: Boolean = false)

  case class Scalafmt(
      enabled: Boolean = true,
      version: String = "1.3.0",
      confPath: Option[RelativePath] = None
  )
  case class Scalafix(
      enabled: Boolean = true,
      confPath: RelativePath = RelativePath(".scalafix.conf")
  )
  // TODO(olafur): re-enable indexJDK after https://github.com/scalameta/language-server/issues/43 is fixed
  case class Search(indexJDK: Boolean = false, indexClasspath: Boolean = true)

  implicit val absolutePathReads: Reads[AbsolutePath] =
    Reads.StringReads
      .filter(s => Paths.get(s).isAbsolute)
      .map(AbsolutePath(_))
  implicit val absolutePathWrites: Writes[AbsolutePath] =
    Writes.StringWrites.contramap(_.toString)
  implicit val relativePathReads: Reads[RelativePath] =
    Reads.StringReads.map(RelativePath(_))
  implicit val relativePathWrites: Writes[RelativePath] =
    Writes.StringWrites.contramap(_.toString)

  // TODO(gabro): Json.format[A] is tedious to write.
  // We should use an annotation macro to cut the boilerplate
  object Scalac {
    implicit val format: OFormat[Scalac] =
      Json.using[Json.WithDefaultValues].format[Scalac]
  }
  object Hover {
    implicit val format: OFormat[Hover] =
      Json.using[Json.WithDefaultValues].format[Hover]
  }
  object Rename {
    implicit val format: OFormat[Rename] =
      Json.using[Json.WithDefaultValues].format[Rename]
  }
  object Scalafmt {
    lazy val defaultConfPath = RelativePath(".scalafmt.conf")
    implicit val format: OFormat[Scalafmt] =
      Json.using[Json.WithDefaultValues].format[Scalafmt]
  }
  object Scalafix {
    implicit val format: OFormat[Scalafix] =
      Json.using[Json.WithDefaultValues].format[Scalafix]
  }
  object Search {
    implicit val format: OFormat[Search] =
      Json.using[Json.WithDefaultValues].format[Search]
  }
  implicit val format: OFormat[Configuration] =
    Json.using[Json.WithDefaultValues].format[Configuration]
}
