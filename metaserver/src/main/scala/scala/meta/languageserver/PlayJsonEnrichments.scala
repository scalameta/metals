package scala.meta.languageserver

import play.api.libs.json.JsError

object PlayJsonEnrichments {
  implicit class XtensionPlayJsonError(val jsError: JsError) extends AnyVal {
    def show: String =
      jsError.errors.iterator
        .map {
          case (path, err) =>
            s"$path: ${err.mkString(", ")}"
        }
        .mkString("; ")
  }
}
