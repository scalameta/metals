package com.google.turbine.testing

import com.google.common.base.CharMatcher
import com.google.common.base.Splitter
import java.io.PrintWriter
import java.io.StringWriter
import org.objectweb.asm.ClassReader
import org.objectweb.asm.util.Printer
import org.objectweb.asm.util.Textifier
import org.objectweb.asm.util.TraceClassVisitor
import scala.jdk.CollectionConverters._

object AsmUtils {
  def textify(bytes: Array[Byte], skipDebug: Boolean): String = {
    val textifier: Printer = new Textifier()
    val sw = new StringWriter()
    new ClassReader(bytes)
      .accept(
        new TraceClassVisitor(null, textifier, new PrintWriter(sw, true)),
        ClassReader.SKIP_FRAMES | ClassReader.SKIP_CODE | (if (skipDebug) ClassReader.SKIP_DEBUG else 0)
      )
    Splitter
      .onPattern("\\R")
      .splitToStream(sw.toString)
      .iterator()
      .asScala
      .map(line => CharMatcher.is(' ').trimTrailingFrom(line))
      .mkString("\n")
  }
}
