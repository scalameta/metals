package scala.meta.internal.metals

import java.text.SimpleDateFormat
import java.util.Calendar

import scala.util.Try

object TimeFormatter {
  private val timeFormat = new SimpleDateFormat("HH-mm-ss-SSS")
  private val dateFormat = new SimpleDateFormat("yyyy-MM-dd")
  private val fullFormat = new SimpleDateFormat("yyyy-MM-dd--HH-mm-ss-SSS")
  def getTime(): String = timeFormat.format(Calendar.getInstance().getTime())
  def getDate(): String = dateFormat.format(Calendar.getInstance().getTime())
  def parse(time: String, date: String): Option[Long] =
    Try { fullFormat.parse(date + "--" + time).getTime() }.toOption
  def hasDateName(str: String): Boolean = Try {
    dateFormat.parse(str)
  }.isSuccess
}
