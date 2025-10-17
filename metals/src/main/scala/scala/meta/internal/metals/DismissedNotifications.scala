package scala.meta.internal.metals

import java.sql.Connection
import java.sql.Timestamp
import java.util.concurrent.TimeUnit

import scala.meta.internal.metals.JdbcEnrichments._

final class DismissedNotifications(conn: () => Connection, time: Time) {

  val Only212Navigation = new Notification(1)
  val IncompatibleSbt = new Notification(2)
  val ImportChanges = new Notification(3)
  val DoctorWarning = new Notification(4)
  val IncompatibleBloop = new Notification(5)
  val ReconnectBsp = new Notification(6)
  val CreateScalafmtFile = new Notification(7)
  val ChangeScalafmtVersion = new Notification(8)
  val UpdateScalafmtConf = new Notification(11)
  val UpdateBloopJson = new Notification(12)
  val ReconnectScalaCli = new Notification(13)
  val ScalaCliImportAuto = new Notification(14)
  val BspErrors = new Notification(15)
  val RequestTimeout = new Notification(16)
  val ScalafixConfAmend = new Notification(17)
  val DebuggeeStartTimeout = new Notification(18)

  val all: List[Notification] = List(
    Only212Navigation,
    IncompatibleSbt,
    ImportChanges,
    DoctorWarning,
    IncompatibleBloop,
    ReconnectBsp,
    CreateScalafmtFile,
    ChangeScalafmtVersion,
    UpdateScalafmtConf,
    UpdateBloopJson,
    ReconnectScalaCli,
    ScalaCliImportAuto,
    BspErrors,
    RequestTimeout,
    ScalafixConfAmend,
    DebuggeeStartTimeout,
  )

  def resetAll(): Unit = {
    all.foreach { notification =>
      if (notification.isDismissed) {
        scribe.info(
          s"Resetting notifications for notification '${notification.notificationName}'"
        )
        notification.reset()
      }
    }
  }

  class Notification(val id: Int)(implicit name: sourcecode.Name) {
    override def toString: String = s"Notification(${name.value}, $id)"
    def notificationName: String = name.value
    def isDismissed: Boolean = {
      val now = new Timestamp(time.currentMillis())
      conn().query {
        "select * from dismissed_notification where id = ? and when_expires > ? limit 1;"
      } { stmt =>
        stmt.setInt(1, id)
        stmt.setTimestamp(2, now)
      }(_ => ()).nonEmpty
    }
    def dismissForever(): Unit = {
      // For some reason, new Timestamp(Long.MaxValue) doesn't work so we will have
      // to do with an arbitrary large number of days.
      dismiss(10000, TimeUnit.DAYS)
    }
    def dismiss(count: Long, unit: TimeUnit): Unit = {
      val sum = time.currentMillis() + unit.toMillis(count)
      if (sum < 0) dismissForever()
      else dismiss(new Timestamp(sum))
    }
    def dismiss(whenExpire: Timestamp): Unit = {
      val now = new Timestamp(time.currentMillis())
      conn().update {
        "insert into dismissed_notification values (?, ?, ?);"
      } { stmt =>
        stmt.setInt(1, id)
        stmt.setTimestamp(2, now)
        stmt.setTimestamp(3, whenExpire)
      }
    }

    def reset(): Unit = {
      conn().update("delete from dismissed_notification where id = ?") { stmt =>
        stmt.setInt(1, id)
      }
    }

    def whenExpires(): Option[Long] = {
      val now = new Timestamp(time.currentMillis())
      val timestamp = conn().query {
        "select when_expires from dismissed_notification where id = ? and when_expires > ? limit 1;"
      } { stmt =>
        stmt.setInt(1, id)
        stmt.setTimestamp(2, now)
      }(rs => rs.getTimestamp(1).getTime()).headOption

      timestamp.map(_ - now.getTime())
    }
  }
}
