package docs

import java.text.SimpleDateFormat
import java.util.Date
import org.jsoup.Jsoup
import scala.collection.JavaConverters._
import scala.meta.internal.metals.BuildInfo
import scala.util.control.NonFatal

case class Snapshot(version: String, lastModified: Date) {
  def date: String = {
    val pattern = new SimpleDateFormat("dd MMM yyyy HH:mm")
    pattern.format(lastModified)
  }
}

object Snapshot {
  def latest(repo: String): Snapshot = {
    if (System.getenv("CI") != null) {
      try {
        fetchLatest(repo)
      } catch {
        case NonFatal(e) =>
          scribe.error("unexpected error fetching SNAPSHOT version", e)
          current
      }
    } else {
      current
    }
  }

  private def current: Snapshot = Snapshot(BuildInfo.metalsVersion, new Date())

  /** Returns the latest published snapshot release, or the current release if. */
  private def fetchLatest(repo: String): Snapshot = {
    val url =
      s"https://oss.sonatype.org/content/repositories/$repo/org/scalameta/metals_2.12/"
    // maven-metadata.xml is consistently outdated so we scrape the "Last modified" column
    // of the HTML page that lists all snapshot releases instead.
    val doc = Jsoup.connect(url).get
    val dateTime = new SimpleDateFormat("EEE MMM d H:m:s z yyyy")
    val snapshots: Seq[Snapshot] = doc.select("tr").asScala.flatMap { tr =>
      val lastModified =
        tr.select("td:nth-child(2)").text()
      val version =
        tr.select("td:nth-child(1)").text().stripSuffix("/")
      if (lastModified.nonEmpty && !version.contains("maven-metadata")) {
        val date = dateTime.parse(lastModified)
        List(Snapshot(version, date))
      } else {
        List()
      }
    }
    if (snapshots.isEmpty) {
      val doc = Jsoup.connect(url + "maven-metadata.xml").get
      val latest = doc.select("latest").text().trim
      val Date = "(\\d\\d\\d\\d)(\\d\\d)(\\d\\d)(\\d\\d)(\\d\\d)(\\d\\d).*".r
      val lastUpdated = doc.select("lastUpdated").text().trim match {
        case Date(year, mon, day, hr, min, sec) =>
          new Date(
            year.toInt - 1900,
            mon.toInt - 1,
            day.toInt,
            hr.toInt,
            min.toInt,
            sec.toInt
          )
        case _ =>
          new Date()
      }
      Snapshot(latest, lastUpdated)
    } else {
      snapshots.maxBy(_.lastModified.getTime)
    }
  }

}
