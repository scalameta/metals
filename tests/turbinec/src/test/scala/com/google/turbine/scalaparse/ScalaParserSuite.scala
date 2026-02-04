package com.google.turbine.scalaparse

import com.google.turbine.diag.SourceFile
import com.google.turbine.testing.TestResources
import munit.FunSuite
import scala.jdk.CollectionConverters._

class ScalaParserSuite extends FunSuite {
  private val cases = List("basic", "package_object", "defaults")

  cases.foreach { name =>
    test(s"golden-$name") {
      val input = TestResources.getResource(getClass, s"testdata/parser/$name.scala")
      val expected = TestResources.getResource(getClass, s"testdata/parser/$name.outline")
      val unit = ScalaParser.parse(new SourceFile(null, input))
      val outline = ScalaOutlinePrinter.print(unit).asScala.toList
      assertEquals(outline, lines(expected))
    }
  }

  private def lines(text: String): List[String] =
    text.split("\\R").toList
}
