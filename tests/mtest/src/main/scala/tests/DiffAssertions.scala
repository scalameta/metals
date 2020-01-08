package tests

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import scala.meta.internal.jdk.CollectionConverters._
import scala.util.control.NonFatal
import fansi.Color
import funsuite.Location

object DiffAssertions extends funsuite.Assertions {
  def assertNoDiffOrPrintObtained(
      obtained: String,
      expected: String,
      obtainedTitle: String,
      expectedTitle: String
  )(implicit loc: Location): Unit = {
    orPrintObtained(
      () => assertNoDiff(obtained, expected, obtainedTitle, expectedTitle),
      obtained
    )
  }

  def assertNoDiff(
      obtained: String,
      expected: String,
      obtainedTitle: String,
      expectedTitle: String
  )(implicit source: Location): Boolean = colored {
    if (obtained.isEmpty && !expected.isEmpty) fail("Obtained empty output!")
    val result = unifiedDiff(obtained, expected, obtainedTitle, expectedTitle)
    if (result.isEmpty) true
    else {
      throw new TestFailedException(
        error2message(
          obtained,
          expected,
          obtainedTitle,
          expectedTitle
        )
      )
    }
  }

  private def error2message(
      obtained: String,
      expected: String,
      obtainedTitle: String,
      expectedTitle: String
  ): String = {
    def header[T](t: T): String = {
      val line = s"=" * (t.toString.length + 3)
      s"$line\n=> $t\n$line"
    }
    def stripTrailingWhitespace(str: String): String =
      str.replaceAll(" \n", "∙\n")
    val sb = new StringBuilder
    if (obtained.length < 1000) {
      sb.append(
        s"""#${header("Obtained")}
            #${renderObtained(stripTrailingWhitespace(obtained))}
            #
            #""".stripMargin('#')
      )
    }
    sb.append(
      s"""#${header("Diff")}
         #${stripTrailingWhitespace(
           unifiedDiff(obtained, expected, obtainedTitle, expectedTitle)
         )}"""
        .stripMargin('#')
    )
    sb.toString()
  }

  def expectNoDiff(obtained: String, expected: String, hint: String = "")(
      implicit loc: Location
  ): Unit = {
    colored {
      assertNoDiff(obtained, expected, hint, hint)
    }
  }
  def colored[T](thunk: => T)(implicit loc: Location): T = {
    try {
      thunk
    } catch {
      case NonFatal(e) =>
        val message = e.getMessage.linesIterator
          .map { line =>
            if (line.startsWith("+")) Color.Green(line)
            else if (line.startsWith("-")) Color.LightRed(line)
            else Color.Reset(line)
          }
          .mkString("\n")
        fail("Failed assertion")
    }
  }

  private def renderObtained(obtained: String): String = {
    val out = new ByteArrayOutputStream()
    renderObtained(obtained, new PrintStream(out))
    out.toString()
  }

  private def renderObtained(obtained: String, out: PrintStream): Unit = {
    obtained.linesIterator.toList match {
      case head +: tail =>
        out.println("    \"\"\"|" + head)
        tail.foreach(line => out.println("       |" + line))
      case head +: Nil =>
        out.println(head)
      case Nil =>
        out.println("obtained is empty")
    }
  }

  def orPrintObtained(thunk: () => Unit, obtained: String): Unit = {
    try thunk()
    catch {
      case ex: Exception =>
        renderObtained(obtained, System.out)
        throw ex
    }
  }

  def unifiedDiff(
      original: String,
      revised: String,
      obtained: String,
      expected: String
  ): String =
    compareContents(
      splitIntoLines(original),
      splitIntoLines(revised),
      obtained,
      expected
    )

  private def splitIntoLines(string: String): Seq[String] =
    string.trim.replace("\r\n", "\n").split("\n")

  private def compareContents(
      original: Seq[String],
      revised: Seq[String],
      obtained: String,
      expected: String
  ): String = {
    val diff = difflib.DiffUtils.diff(original.asJava, revised.asJava)
    if (diff.getDeltas.isEmpty) ""
    else
      difflib.DiffUtils
        .generateUnifiedDiff(obtained, expected, original.asJava, diff, 1)
        .asScala
        .mkString("\n")
  }
}
