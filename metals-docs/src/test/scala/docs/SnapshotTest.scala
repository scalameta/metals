package docs

import java.time._
import tests.BaseSuite

object SnapshotTest extends BaseSuite {

  def checkZDTParse(zdtString: String, expected: ZonedDateTime): Unit =
    test("Maven Repo ZonedDateTime Parse") {
      assert(ZonedDateTime.parse(zdtString, Snapshot.zdtFormatter) == expected)
    }

  def checkMavenMetadataParse(
      mavenMetadataString: String,
      expected: LocalDateTime
  ): Unit =
    test("Maven Metadata LocalDateTime Parse") {
      assert(
        LocalDateTime.parse(
          mavenMetadataString,
          Snapshot.mavenMetadataLastUpdatedFormatter
        ) == expected
      )
    }

  def checkSnapshotDateFormatString(
      snapshot: Snapshot,
      expected: String
  ): Unit =
    test("Snapshot Date Format String") {
      assert(snapshot.date == expected)
    }

  checkZDTParse(
    "Mon Apr 08 04:09:49 UTC 2019",
    ZonedDateTime.of(2019, 4, 8, 4, 9, 49, 0, ZoneId.of("UTC"))
  )
  checkMavenMetadataParse(
    "20190408041011",
    LocalDateTime.of(2019, 4, 8, 4, 10, 11, 0)
  )
  checkSnapshotDateFormatString(
    Snapshot("1", LocalDateTime.of(2019, 4, 8, 4, 10, 11, 0)),
    "08 Apr 2019 04:10"
  )
}
