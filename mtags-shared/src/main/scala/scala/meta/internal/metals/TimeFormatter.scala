package scala.meta.internal.metals

import java.text.SimpleDateFormat
import java.util.Calendar

import scala.util.Try

object TimeFormatter {
  private val format = new SimpleDateFormat("dd-MM-yyyy--HH-mm-ss-SSS")
  def getTime(): String = format.format(Calendar.getInstance().getTime())
  def parse(date: String): Option[Long] =
    Try { format.parse(date).getTime() }.toOption
}
