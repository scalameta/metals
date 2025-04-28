package scala.meta.internal.metals

import java.net.URI
import java.util.Optional

import scala.meta.internal.mtags.CommonMtagsEnrichments._
import scala.meta.internal.mtags.MD5
import scala.meta.pc.{reports => jreports}

case class Report(
    name: String,
    text: String,
    shortSummary: String,
    path: Optional[URI] = Optional.empty(),
    id: Optional[String] = Optional.empty(),
    error: Option[Throwable] = None
) extends jreports.Report {

  def extend(moreInfo: String): Report =
    this.copy(
      text = s"""|${this.text}
                 |$moreInfo"""".stripMargin
    )

  def fullText(withIdAndSummary: Boolean): String = {
    val sb = new StringBuilder
    if (withIdAndSummary) {
      id.asScala
        .orElse(
          error.map(error =>
            MD5.compute(s"${name}:${error.getStackTrace().mkString("\n")}")
          )
        )
        .foreach(id => sb.append(s"${Report.idPrefix}$id\n"))
    }
    path.asScala.foreach(path => sb.append(s"$path\n"))
    error match {
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
      sb.append(s"""|${Report.summaryTitle}
                    |
                    |$shortSummary""".stripMargin)
    sb.result()
  }
}

object Report {

  def apply(
      name: String,
      text: String,
      error: Throwable,
      path: Option[URI]
  ): Report =
    Report(
      name,
      text,
      shortSummary = error.toString(),
      path = path.asJava,
      error = Some(error)
    )

  def apply(name: String, text: String, error: Throwable): Report =
    Report(name, text, error, path = None)

  val idPrefix = "error id: "
  val summaryTitle = "#### Short summary: "
}

sealed trait ReportLevel {
  def isVerbose: Boolean
}

object ReportLevel {
  case object Info extends ReportLevel {
    def isVerbose = false
  }

  case object Debug extends ReportLevel {
    def isVerbose = true
  }

  def fromString(level: String): ReportLevel =
    level match {
      case "debug" => Debug
      case _ => Info
    }
}
