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

  def latest(
      useSnapshot: Boolean,
      binaryVersion: String,
      retry: Int = 5,
  ): Snapshot = {
    if (System.getenv("CI") != null) {
      try {
        fetchLatest(useSnapshot, binaryVersion)
      } catch {
        case NonFatal(e) if retry > 0 =>
          scribe.error(
            "unexpected error fetching SNAPSHOT version, retrying...",
            e,
          )
          latest(useSnapshot, binaryVersion, retry - 1)
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

  private def findModifiedFromDirectory(
      url: String,
      version: String,
  ): List[Snapshot] = {
    val modified = Jsoup
      .connect(url + version)
      .get
      .select("tr")
      .asScala
      .flatMap { tr =>
        val lastModified =
          tr.select("td:nth-child(2)").text()
        if (lastModified.nonEmpty)
          Some(lastModified)
        else
          None
      }
      .headOption
    if (modified.nonEmpty)
      List(
        Snapshot(
          version,
          ZonedDateTime
            .parse(modified.get, zdtFormatter)
            .toLocalDateTime,
        )
      )
    else
      Nil
  }

  /**
   * Returns the latest published snapshot release, or the current release if.
   */
  private def fetchLatest(
      useSnapshot: Boolean,
      binaryVersion: String,
  ): Snapshot = {
    val url =
      if (useSnapshot)
        s"https://central.sonatype.com/service/rest/repository/browse/maven-snapshots/org/scalameta/metals_$binaryVersion/"
      else
        s"https://repo1.maven.org/maven2/org/scalameta/metals_$binaryVersion/"
    // maven-metadata.xml is consistently outdated so we scrape the "Last modified" column
    // of the HTML page that lists all snapshot releases instead.
    val doc = Jsoup.connect(url).get
    val snapshots: Seq[Snapshot] = doc
      .select("tr")
      .asScala
      .flatMap { tr =>
        val lastModified =
          tr.select("td:nth-child(2)").text()
        val version =
          tr.select("td:nth-child(1)").text().stripSuffix("/")
        if (lastModified.nonEmpty && !version.contains("maven-metadata")) {
          val date: ZonedDateTime =
            ZonedDateTime.parse(lastModified, zdtFormatter)
          List(Snapshot(version, date.toLocalDateTime))
        } else if (version.nonEmpty && !version.contains("Parent")) {
          /* snapshots don't have modified dates in the main directory
          so we need to scrape the exact version directory */
          findModifiedFromDirectory(url, version)
        } else {
          Nil
        }
      }
      .toSeq
    if (snapshots.isEmpty) {
      val doc = Jsoup.connect(url + "maven-metadata.xml").get
      val latest = doc.select("latest").text().trim
      val lastUpdated: LocalDateTime =
        Try(
          LocalDateTime.parse(
            doc.select("lastUpdated").text().trim,
            mavenMetadataLastUpdatedFormatter,
          )
        ).getOrElse(LocalDateTime.now())
      Snapshot(latest, lastUpdated)
    } else {
      snapshots.maxBy(_.lastModified)
    }
  }

}
