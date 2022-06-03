package tests

import scala.collection.mutable

import scala.meta.internal.metals.JsonParser
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.{BuildInfo => V}

import munit.TestOptions
import org.eclipse.{lsp4j => l}

abstract class BaseAnalyzeStacktraceSuite(name: String)
    extends BaseLspSuite(name) {

  private def getExpected(code: String): Map[Int, Int] = {
    val result: mutable.Buffer[(Int, Int)] = mutable.Buffer()
    for ((line, idx) <- code.split('\n').zipWithIndex) {
      if (line.contains("<<") && line.contains(">>")) {
        val marker = Integer.valueOf(
          line.substring(line.indexOf("<<") + 2, line.indexOf(">>"))
        )
        result += ((marker, idx))
      }
    }
    result.toList.toMap
  }

  private def prepare(code: String): String = {
    code.replaceAll("<<.*>>", "")
  }

  def check(
      name: TestOptions,
      code: String,
      stacktrace: String,
      filename: String = "Main.scala",
      scalaVersion: String = V.scala3,
      dependency: String = "",
  )(implicit loc: munit.Location): Unit = {
    val locationParser = new JsonParser.Of[l.Location]
    test(name) {
      cleanWorkspace()
      for {
        _ <- initialize(
          s"""
             |/metals.json
             |{ 
             |  "a": { 
             |    "scalaVersion": "$scalaVersion",
             |    "libraryDependencies": [ $dependency ]
             |   }
             |}
             |/a/src/main/scala/a/$filename
             |${prepare(code)}
             |""".stripMargin
        )
        _ <- server.didOpen(s"a/src/main/scala/a/$filename")
        lenses = server.analyzeStacktrace(stacktrace)
        output =
          lenses.map { cl =>
            val line = cl.getCommand().getArguments.asScala.toSeq match {
              case Seq(locationParser.Jsonized(location)) =>
                location.getRange().getStart().getLine()
            }
            cl.getRange.getStart.getLine -> line
          }.toMap
        _ = assertEquals(output, getExpected(code))
      } yield ()
    }
  }
}
