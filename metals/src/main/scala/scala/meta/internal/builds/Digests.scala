package scala.meta.internal.builds

import java.sql.Connection
import java.sql.Timestamp
import scala.meta.internal.builds.Digest.Status
import scala.meta.internal.metals.JdbcEnrichments._
import scala.meta.internal.metals.Time

/**
 * Wrapper around the sbt_digest sql table.
 */
final class Digests(conn: () => Connection, time: Time) {

  def setStatus(md5Digest: String, status: Status): Int =
    conn().update(
      s"insert into sbt_digest values (?, ?, ?);"
    ) { stmt =>
      val timestamp = new Timestamp(time.currentMillis())
      stmt.setString(1, md5Digest)
      stmt.setByte(2, status.value.toByte)
      stmt.setTimestamp(3, timestamp)
    }

  def last(): Option[Digest] =
    conn()
      .query(
        "select md5, status, when_recorded from sbt_digest d order by d.when_recorded desc limit 1;"
      )(_ => ()) { rs =>
        val digest = rs.getString(1)
        val n = rs.getByte(2).toInt
        val status = Digest.Status.all
          .find(_.value == n)
          .getOrElse(Digest.Status.Unknown(n))
        val timestamp = rs.getTimestamp(3).getTime
        Digest(digest, status, timestamp)
      }
      .headOption

  def byDigest(md5: String): List[Digest] =
    conn()
      .query(
        "select status, when_recorded from sbt_digest where md5 = ?;"
      )(
        _.setString(1, md5)
      ) { rs =>
        val n = rs.getByte(1).toInt
        val status = Digest.Status.all
          .find(_.value == n)
          .getOrElse(Digest.Status.Unknown(n))
        val timestamp = rs.getTimestamp(2).getTime
        Digest(md5, status, timestamp)
      }

  def getStatus(md5Digest: String): Option[Digest.Status] = {
    val all = byDigest(md5Digest)
    if (all.isEmpty) None
    else Some(all.maxBy(_.millis).status)
  }

}
