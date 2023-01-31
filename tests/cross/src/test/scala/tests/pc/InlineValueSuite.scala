package tests.pc

import java.net.URI

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.metals.TextEdits
import scala.meta.internal.mtags.CommonMtagsEnrichments
import scala.meta.internal.pc.InlineValueProvider.{Errors => InlineErrors}
import scala.meta.pc.DisplayableException

import munit.Location
import munit.TestOptions
import org.eclipse.{lsp4j => l}
import tests.BaseCodeActionSuite

class InlineValueSuite extends BaseCodeActionSuite with CommonMtagsEnrichments {

  checkEdit(
    "inline-local",
    """|object Main {
       |  def u(): Unit = {
       |    val o: Int = 1
       |    val p: Int = <<o>> + 2
       |  } 
       |}""".stripMargin,
    """|object Main {
       |  def u(): Unit = {
       |    val p: Int = 1 + 2
       |  } 
       |}""".stripMargin,
  )

  checkEdit(
    "inline-local-same-name",
    """|object Main {
       | val a = { val a = 1; val b = <<a>> + 1 }
       |}""".stripMargin,
    """|object Main {
       | val a = { val b = 1 + 1 }
       |}""".stripMargin,
  )

  checkEdit(
    "inline-local-same-name2",
    """|object Main {
       |  val b = {
       |    val a = 1
       |    val b = <<a>> + 1
       |  }
       |  val a = 3
       |}""".stripMargin,
    """|object Main {
       |  val b = {
       |    val b = 1 + 1
       |  }
       |  val a = 3
       |}""".stripMargin,
  )

  checkEdit(
    "inline-local-same-name3",
    """|object Main {
       |  val b = {
       |    val <<a>> = 1
       |    val b = a + 1
       |  }
       |  val a = 3
       |  val g = a
       |}""".stripMargin,
    """|object Main {
       |  val b = {
       |    val b = 1 + 1
       |  }
       |  val a = 3
       |  val g = a
       |}""".stripMargin,
  )

  checkEdit(
    "inline-all-local",
    """|object Main {
       |  def u(): Unit = {
       |    val <<o>>: Int = 1
       |    val p: Int = o + 2
       |    val i: Int = o + 3
       |  } 
       |}""".stripMargin,
    """|object Main {
       |  def u(): Unit = {
       |    val p: Int = 1 + 2
       |    val i: Int = 1 + 3
       |  } 
       |}""".stripMargin,
  )

  checkEdit(
    "inline-all-local-val",
    """|object Main {
       |  val u(): Unit = {
       |    val <<o>>: Int = 1
       |    val p: Int = o + 2
       |    val i: Int = o + 3
       |  } 
       |}""".stripMargin,
    """|object Main {
       |  val u(): Unit = {
       |    val p: Int = 1 + 2
       |    val i: Int = 1 + 3
       |  } 
       |}""".stripMargin,
  )

  checkEdit(
    "inline-local-brackets",
    """|object Main {
       |  def u(): Unit = {
       |    val o: Int = 1 + 6
       |    val p: Int = 2 - <<o>>
       |    val k: Int = o
       |  } 
       |}""".stripMargin,
    """|object Main {
       |  def u(): Unit = {
       |    val o: Int = 1 + 6
       |    val p: Int = 2 - (1 + 6)
       |    val k: Int = o
       |  } 
       |}""".stripMargin,
  )

  checkEdit(
    "inline-all-local-brackets",
    """|object Main {
       |  def u(): Unit = {
       |    val h: Int = 9
       |    val <<o>>: Int = 1 + 6
       |    val p: Int = h - o
       |    val k: Int = o
       |  } 
       |}""".stripMargin,
    """|object Main {
       |  def u(): Unit = {
       |    val h: Int = 9
       |    val p: Int = h - (1 + 6)
       |    val k: Int = 1 + 6
       |  } 
       |}""".stripMargin,
  )

  checkEdit(
    "inline-not-local",
    """|object Main {
       |  val o: Int = 6
       |  val p: Int = 2 - <<o>>
       |}""".stripMargin,
    """|object Main {
       |  val o: Int = 6
       |  val p: Int = 2 - 6
       |}""".stripMargin,
  )

  checkEdit(
    "inline-not-local-pkg",
    """|package m
       |object Main {
       |  val o: Int = 6
       |  val p: Int = 2 - <<o>>
       |}""".stripMargin,
    """|package m
       |object Main {
       |  val o: Int = 6
       |  val p: Int = 2 - 6
       |}""".stripMargin,
  )

  checkEdit(
    "lambda-apply",
    """|object Main {
       |  def demo = {
       |    val plus1 = (x: Int) => x + 1
       |    println(<<plus1>>(1))
       |  }
       |}""".stripMargin,
    """|object Main {
       |  def demo = {
       |    println(((x: Int) => x + 1)(1))
       |  }
       |}""".stripMargin,
  )

  checkEdit(
    "lambda-as-arg",
    """|object Main {
       |  def demo = {
       |    val plus1 = (x: Int) => x + 1
       |    val plus2 = <<plus1>>
       |  }
       |}""".stripMargin,
    """|object Main {
       |  def demo = {
       |    val plus2 = (x: Int) => x + 1
       |  }
       |}""".stripMargin,
  )

  checkErorr(
    "inline-all-not-local",
    """|object Main {
       |  val <<o>>: Int = 6
       |  val p: Int = 2 - o
       |}""".stripMargin,
    InlineErrors.notLocal,
  )

  def checkEdit(
      name: TestOptions,
      original: String,
      expected: String,
      compat: Map[String, String] = Map.empty,
      filename: String = "file:/A.scala",
  )(implicit location: Location): Unit =
    test(name) {
      val edits = getInlineEdits(original, filename)
      val (code, _, _) = params(original)
      val obtained = TextEdits.applyEdits(code, edits)
      assertNoDiff(obtained, getExpected(expected, compat, scalaVersion))
    }

  def checkErorr(
      name: TestOptions,
      original: String,
      expectedError: String,
      filename: String = "file:/A.scala",
  )(implicit location: Location): Unit =
    test(name) {
      try {
        getInlineEdits(original, filename)
        fail("No error found")
      } catch {
        case e: Exception if (e.getCause match {
              case _: DisplayableException => true
              case _ => false
            }) =>
          assertNoDiff(e.getCause.getMessage, expectedError)
      }
    }

  def getInlineEdits(
      original: String,
      filename: String,
  ): List[l.TextEdit] = {
    val (code, _, offset) = params(original)
    val result = presentationCompiler
      .inlineValue(
        CompilerOffsetParams(
          URI.create(filename),
          code,
          offset,
          cancelToken,
        )
      )
      .get()
    result.asScala.toList
  }

}
