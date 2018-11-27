package scala.meta.internal.metals

import java.sql.Connection
import java.sql.Timestamp
import scala.meta.internal.metals.JdbcEnrichments._
import scala.meta.internal.metals.SbtDigest.Status

/**
 * Wrapper around the sbt_digest sql table.
 */
final class SbtDigests(conn: Connection, time: Time) {

  def setStatus(md5Digest: String, status: Status): Int =
    conn.update(
      s"insert into sbt_digest values (?, ?, ?);"
    ) { stmt =>
      val timestamp = new Timestamp(time.millis())
      stmt.setString(1, md5Digest)
      stmt.setByte(2, status.value.toByte)
      stmt.setTimestamp(3, timestamp)
    }

  def last(): Option[SbtDigest] =
    conn
      .query(
        "select md5, status, when_recorded from sbt_digest d order by d.when_recorded desc limit 1;"
      )(_ => ()) { rs =>
        val digest = rs.getString(1)
        val n = rs.getByte(2).toInt
        val status = SbtDigest.Status.all
          .find(_.value == n)
          .getOrElse(SbtDigest.Status.Unknown(n))
        val timestamp = rs.getTimestamp(3).getTime
        SbtDigest(digest, status, timestamp)
      }
      .headOption

  def byDigest(md5: String): List[SbtDigest] =
    conn
      .query(
        "select status, when_recorded from sbt_digest where md5 = ?;"
      )(
        _.setString(1, md5)
      ) { rs =>
        val n = rs.getByte(1).toInt
        val status = SbtDigest.Status.all
          .find(_.value == n)
          .getOrElse(SbtDigest.Status.Unknown(n))
        val timestamp = rs.getTimestamp(2).getTime
        SbtDigest(md5, status, timestamp)
      }

  def getStatus(md5Digest: String): Option[SbtDigest.Status] = {
    val all = byDigest(md5Digest)
    if (all.isEmpty) None
    else Some(all.maxBy(_.millis).status)
  }

}
