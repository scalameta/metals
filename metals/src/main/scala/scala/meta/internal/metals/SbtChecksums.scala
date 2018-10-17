package scala.meta.internal.metals

import java.sql.Connection
import scala.meta.internal.metals.JdbcEnrichments._
import scala.meta.internal.metals.SbtChecksum.Status

/**
 * Wrapper around the sbt_checksum sql table.
 */
final class SbtChecksums(conn: Connection) {

  def setStatus(md5Digest: String, status: Status): Int =
    conn.update(
      s"merge into sbt_checksum key(md5_digest) values (?, ?);"
    ) { stmt =>
      stmt.setString(1, md5Digest)
      stmt.setByte(2, status.value.toByte)
    }

  def getStatus(md5Digest: String): Option[SbtChecksum.Status] =
    conn
      .query("select status from sbt_checksum where md5_digest = ?;")(
        _.setString(1, md5Digest)
      ) { rs =>
        val n = rs.getByte(1).toInt
        val status = SbtChecksum.Status.all
          .find(_.value == n)
          .getOrElse(SbtChecksum.Status.Unknown(n))
        SbtChecksum(md5Digest, status)
      }
      .headOption
      .map(_.status)

}
