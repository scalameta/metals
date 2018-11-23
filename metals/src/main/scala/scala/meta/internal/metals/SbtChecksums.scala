package scala.meta.internal.metals

import java.sql.Connection
import java.sql.Timestamp
import scala.meta.internal.metals.JdbcEnrichments._
import scala.meta.internal.metals.SbtChecksum.Status

/**
 * Wrapper around the sbt_checksum sql table.
 */
final class SbtChecksums(conn: Connection, time: Time) {

  def setStatus(md5Digest: String, status: Status): Int =
    conn.update(
      s"insert into sbt_fingerprint_event values (?, ?, ?);"
    ) { stmt =>
      val timestamp = new Timestamp(time.millis())
      stmt.setString(1, md5Digest)
      stmt.setByte(2, status.value.toByte)
      stmt.setTimestamp(3, timestamp)
    }

  def last(): Option[SbtChecksum] =
    conn
      .query(
        "select md5_digest, status from sbt_fingerprint_event f order by f.when_happened desc limit 1;"
      )(_ => ()) { rs =>
        val digest = rs.getString(1)
        val n = rs.getByte(2).toInt
        val status = SbtChecksum.Status.all
          .find(_.value == n)
          .getOrElse(SbtChecksum.Status.Unknown(n))
        SbtChecksum(digest, status)
      }
      .headOption

  def byDigest(md5Digest: String): List[(Status, Timestamp)] =
    conn
      .query(
        "select status, when_happened from sbt_fingerprint_event where md5_digest = ?;"
      )(
        _.setString(1, md5Digest)
      ) { rs =>
        val n = rs.getByte(1).toInt
        val status = SbtChecksum.Status.all
          .find(_.value == n)
          .getOrElse(SbtChecksum.Status.Unknown(n))
        val timestamp = rs.getTimestamp(2)
        (status, timestamp)
      }

  def getStatus(md5Digest: String): Option[SbtChecksum.Status] = {
    val all = byDigest(md5Digest)
    if (all.isEmpty) None
    else {
      val (status, _) = all.maxBy {
        case (_, timestamp) => timestamp.getTime
      }
      Some(status)
    }
  }

}
