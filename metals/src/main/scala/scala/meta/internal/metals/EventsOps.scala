package scala.meta.internal.metals

import scala.meta.infra.Event
import scala.meta.internal.semanticdb.Language

object EventsOps {
  implicit class MetricsEventOps(event: Event) {
    def withLanguage(language: Language): Event =
      event.withLabel("language", language.toString.toLowerCase)

    def withLanguage(language: String): Event =
      event.withLabel("language", language)

    def withOptional(key: String, value: Option[String]): Event =
      value.fold(event)(event.withLabel(key, _))
  }
}
