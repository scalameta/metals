package scala.meta.internal.pc

import java.{util => ju}

import scala.meta.internal.mtags.CommonMtagsEnrichments._
import scala.meta.pc.Report

case class StandardReport(
    name: String,
    text: String,
    shortSummary: String,
    path: ju.Optional[String],
    id: ju.Optional[String],
    error: ju.Optional[Throwable]
) extends Report {

  def extend(moreInfo: String): StandardReport =
    this.copy(
      text = s"""|${this.text}
                 |$moreInfo"""".stripMargin
    )

  def fullText(withIdAndSummary: Boolean): String = {
    val sb = new StringBuilder
    if (withIdAndSummary) {
      id.asScala.foreach(id => sb.append(s"${StandardReport.idPrefix}$id\n"))
    }
    path.asScala.foreach(path => sb.append(s"$path\n"))
    error.asScala match {
      case Some(error) =>
        sb.append(
          s"""|### $error
              |
              |$text
              |
              |#### Error stacktrace:
              |
              |```
              |${error.getStackTrace().mkString("\n\t")}
              |```
              |""".stripMargin
        )
      case None => sb.append(s"$text\n")
    }
    if (withIdAndSummary)
      sb.append(s"""|${StandardReport.summaryTitle}
                    |
                    |$shortSummary""".stripMargin)
    sb.result()
  }
}

object StandardReport {
  def apply(
      name: String,
      text: String,
      shortSummary: String,
      path: Option[String] = None,
      id: Option[String] = None,
      error: Option[Throwable] = None
  ): StandardReport =
    StandardReport(
      name,
      text,
      shortSummary,
      path.asJava,
      id.asJava,
      error.asJava
    )

  def apply(
      name: String,
      text: String,
      error: Throwable,
      path: Option[String]
  ): StandardReport =
    StandardReport(
      name,
      text,
      shortSummary = error.toString(),
      path = path,
      error = Some(error)
    )

  def apply(name: String, text: String, error: Throwable): StandardReport =
    StandardReport(name, text, error, path = None)

  val idPrefix = "error id: "
  val summaryTitle = "#### Short summary: "
}
