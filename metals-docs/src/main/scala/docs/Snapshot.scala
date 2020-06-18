package docs

import java.time._
import java.time.format.DateTimeFormatter

import scala.util.Try
import scala.util.control.NonFatal

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.BuildInfo

import org.jsoup.Jsoup

case class Snapshot(version: String, lastModified: LocalDateTime) {
  def date: String = Snapshot.snapshotOutputFormatter.format(lastModified)
}

object Snapshot {
  private val zdtFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss zzz uuuu")
  private val mavenMetadataLastUpdatedFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("uuuuMMddHHmmss")
  private val snapshotOutputFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd MMM uuuu HH:mm")
  private implicit val localDateTimeOrdering: Ordering[LocalDateTime] =
    Ordering.fromLessThan[LocalDateTime]((a, b) => a.compareTo(b) < 0)

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

  private def current: Snapshot =
    Snapshot(BuildInfo.metalsVersion, LocalDateTime.now())

  /**
   * Returns the latest published snapshot release, or the current release if. */
  private def fetchLatest(repo: String): Snapshot = {
    val url =
      s"https://oss.sonatype.org/content/repositories/$repo/org/scalameta/metals_2.12/"
    // maven-metadata.xml is consistently outdated so we scrape the "Last modified" column
    // of the HTML page that lists all snapshot releases instead.
    val doc = Jsoup.connect(url).get
    val snapshots: Seq[Snapshot] = doc.select("tr").asScala.flatMap { tr =>
      val lastModified =
        tr.select("td:nth-child(2)").text()
      val version =
        tr.select("td:nth-child(1)").text().stripSuffix("/")
      if (lastModified.nonEmpty && !version.contains("maven-metadata")) {
        val date: ZonedDateTime =
          ZonedDateTime.parse(lastModified, zdtFormatter)
        List(Snapshot(version, date.toLocalDateTime))
      } else {
        List()
      }
    }
    if (snapshots.isEmpty) {
      val doc = Jsoup.connect(url + "maven-metadata.xml").get
      val latest = doc.select("latest").text().trim
      val lastUpdated: LocalDateTime =
        Try(
          LocalDateTime.parse(
            doc.select("lastUpdated").text().trim,
            mavenMetadataLastUpdatedFormatter
          )
        ).getOrElse(LocalDateTime.now())
      Snapshot(latest, lastUpdated)
    } else {
      snapshots.maxBy(_.lastModified)
    }
  }

}
