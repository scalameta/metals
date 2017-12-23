package scala.meta.languageserver

import play.api.libs.json._
import play.api.libs.functional.syntax._

import org.langmeta.AbsolutePath
import org.langmeta.RelativePath
import java.nio.file.Paths

import Configuration._
import play.api.libs.json.OFormat

case class Configuration(
  scalafmt: Scalafmt = Scalafmt(),
  scalafix: Scalafix = Scalafix(),
  indexing: Indexing = Indexing(),
  experimental: Experimental = Experimental()
)

object Configuration {

  case class Experimental(
      completions: Completions = Completions(),
      hover: Hover = Hover(),
      rename: Rename = Rename()
  )
  case class Completions(enable: Boolean = false)
  case class Hover(enable: Boolean = false)
  case class Rename(enable: Boolean = false)

  case class Scalafmt(enable: Boolean = true, confPath: RelativePath = RelativePath(".scalafmt.conf"))
  case class Scalafix(enable: Boolean = true, confPath: RelativePath = RelativePath(".scalafix.conf"))
  // TODO(olafur): re-enable indexJDK after https://github.com/scalameta/language-server/issues/43 is fixed
  case class Indexing(jdk: Boolean = false, classpath: Boolean = true)

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
  object Completions {
    implicit val format: OFormat[Completions] = Json.using[Json.WithDefaultValues].format[Completions]
  }
  object Hover {
    implicit val format: OFormat[Hover] = Json.format[Hover]
  }
  object Rename {
    implicit val format: OFormat[Rename] = Json.format[Rename]
  }
  object Experimental {
    implicit val format: OFormat[Experimental] = Json.format[Experimental]
  }
  object Scalafmt {
    implicit val format: OFormat[Scalafmt] = Json.format[Scalafmt]
  }
  object Scalafix {
    implicit val format: OFormat[Scalafix] = Json.format[Scalafix]
  }
  object Indexing {
    implicit val format: OFormat[Indexing] = Json.format[Indexing]
  }
  implicit val format: OFormat[Configuration] = Json.format[Configuration]
}
