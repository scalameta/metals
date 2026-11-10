package scala.meta.internal.metals.config

import com.google.googlejavaformat.java.JavaFormatterOptions.Style

sealed abstract class GoogleJavaFormatStyle(val toJavaStyle: Style)

object GoogleJavaFormatStyle {
  case object Google extends GoogleJavaFormatStyle(Style.GOOGLE)
  case object Aosp extends GoogleJavaFormatStyle(Style.AOSP)
}
