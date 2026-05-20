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
       |}""".stripMargin
  )

  checkEdit(
    "inline-local-same-name",
    """|object Main {
       | val a = { val a = 1; val b = <<a>> + 1 }
       |}""".stripMargin,
    """|object Main {
       | val a = { val b = 1 + 1 }
       |}""".stripMargin
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
       |}""".stripMargin
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
       |}""".stripMargin
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
       |}""".stripMargin
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
       |}""".stripMargin
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
       |}""".stripMargin
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
       |}""".stripMargin
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
       |}""".stripMargin
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
       |}""".stripMargin
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
       |}""".stripMargin
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
       |}""".stripMargin
  )

  checkEdit(
    "lambda-param-no-shadow",
    """|object Main {
       |  def test(x: Int) = {
       |    val pq = List(1, 2, 3).map(x => x + 1)
       |    <<pq>>.sum
       |  }
       |}""".stripMargin,
    """|object Main {
       |  def test(x: Int) = {
       |    List(1, 2, 3).map(x => x + 1).sum
       |  }
       |}""".stripMargin
  )

  checkError(
    "inline-all-not-local",
    """|object Main {
       |  val <<o>>: Int = 6
       |  val p: Int = 2 - o
       |}""".stripMargin,
    InlineErrors.notLocal
  )

  checkEdit(
    "for-comprehension",
    """|object Main {
       |val a =
       |  for {
       |    i <- List(1,2,3)
       |  } yield i + 1
       |val b = <<a>>.map(_ + 1)
       |}""".stripMargin,
    """|object Main {
       |val a =
       |  for {
       |    i <- List(1,2,3)
       |  } yield i + 1
       |val b = (for {
       |    i <- List(1,2,3)
       |  } yield i + 1).map(_ + 1)
       |}""".stripMargin
  )

  checkEdit(
    "bracktes-add",
    """|object Main {
       |  val b = 1 + (2 + 3)
       |  val c = <<b>>
       |}""".stripMargin,
    """|object Main {
       |  val b = 1 + (2 + 3)
       |  val c = 1 + (2 + 3)
       |}""".stripMargin
  )

  // --- different possibilites of conflicts ----------
  checkError(
    "scoping",
    """|package scala.net.com.ooo
       |object Demo {
       |  val j: Int = 5
       |  val f: Int = 4 - j
       |
       |  def m() = {
       |    val j = 10
       |    val z = <<f>> + 1
       |  }
       |}""".stripMargin,
    InlineErrors.variablesAreShadowed("scala.net.com.ooo.Demo.j")
  )

  checkError(
    "scoping-class",
    """|class Demo {
       |  val j: Int = 5
       |  val f: Int = 4 - j
       |
       |  def m() = {
       |    val j = 10
       |    val z = <<f>> + 1
       |  }
       |}""".stripMargin,
    InlineErrors.variablesAreShadowed("Demo.j")
  )

  // Note: we do not check if summoned implicts change when inlining
  checkEdit(
    "scoping-implicit",
    """|object Demo {
       |  implicit val b : Boolean = true
       |  def myF(implicit b : Boolean): Int = if(b) 0 else 1
       |  val f: Int = myF
       |
       |  def m() = {
       |    implicit val v : Boolean = false
       |    val z = <<f>>
       |  }
       |}""".stripMargin,
    """|object Demo {
       |  implicit val b : Boolean = true
       |  def myF(implicit b : Boolean): Int = if(b) 0 else 1
       |  val f: Int = myF
       |
       |  def m() = {
       |    implicit val v : Boolean = false
       |    val z = myF
       |  }
       |}""".stripMargin
  )

  checkEdit(
    "i6924",
    """|object O {
       |  def test(n: Int) = {
       |    val isOne = n == 1
       |    <<i>>sOne
       |  }
       |}
       |""".stripMargin,
    """|object O {
       |  def test(n: Int) = {
       |    n == 1
       |  }
       |}
       |""".stripMargin
  )

  checkEdit(
    "i6924-2",
    """|object O {
       |  def ==(o: O) = false
       |}
       |object P {
       |  def test() = {
       |    val isOne = O == O
       |    <<i>>sOne
       |  }
       |}
       |""".stripMargin,
    """|object O {
       |  def ==(o: O) = false
       |}
       |object P {
       |  def test() = {
       |    O == O
       |  }
       |}
       |""".stripMargin
  )

  checkError(
    "scoping-packages",
    """|package a
       |object A {
       |  val aaa = 1
       |}
       |package b;
       |object Demo {
       |  import a.A.aaa
       |  val inl = aaa
       |  def m() = {
       |    val aaa = 3
       |    <<inl>>
       |  }
       |}""".stripMargin,
    InlineErrors.variablesAreShadowed("a.A.aaa")
  )

  checkError(
    "bad-scoping",
    """|object Demo {
       |  def oo(j : Int) = {
       |    val m = j + 3
       |    def kk() = {
       |      val j = 0
       |      <<m>>
       |    }
       |  }
       |}""".stripMargin,
    InlineErrors.variablesAreShadowed("Demo.oo.j"),
    compat = Map(
      "2" -> InlineErrors.variablesAreShadowed("Demo.j")
    )
  )

  checkError(
    "bad-scoping-2",
    """|class A {
       |  val k = 3
       |  val l = k + 2
       |  case class B() {
       |     val k = 5
       |     val m = <<l>> + 3
       |  }
       |}""".stripMargin,
    InlineErrors.variablesAreShadowed("A.k")
  )

  checkError(
    "bad-scoping-3",
    """|class T {
       |    val a = 1
       |}
       |
       |class O {
       |  val t = new T()
       |  import t._
       |  val bb = a + a
       |
       |  class Inner {
       |    val a = 123
       |    val cc = <<b>>b
       |  }
       |}
       |""".stripMargin,
    InlineErrors.variablesAreShadowed("T.a")
  )

  def checkEdit(
      name: TestOptions,
      original: String,
      expected: String,
      compat: Map[String, String] = Map.empty,
      filename: String = "file:/A.scala"
  )(implicit location: Location): Unit =
    test(name) {
      val edits = getInlineEdits(original, filename)
      val (code, _, _) = params(original)
      val obtained = TextEdits.applyEdits(code, edits)
      assertNoDiff(obtained, getExpected(expected, compat, scalaVersion))
    }

  def checkError(
      name: TestOptions,
      original: String,
      expectedError: String,
      compat: Map[String, String] = Map.empty,
      filename: String = "file:/A.scala"
  )(implicit location: Location): Unit =
    test(name) {
      try {
        val edits = getInlineEdits(original, filename)
        val (code, _, _) = params(original)
        val obtained = TextEdits.applyEdits(code, edits)
        fail(s"""|No error found, obtained:
                 |$obtained""".stripMargin)
      } catch {
        case e: Exception if (e.getCause match {
              case _: DisplayableException => true
              case _ => false
            }) =>
          assertNoDiff(
            e.getCause.getMessage,
            getExpected(expectedError, compat, scalaVersion)
          )
      }
    }

  def getInlineEdits(
      original: String,
      filename: String
  ): List[l.TextEdit] = {
    val (code, _, offset) = params(original)
    val result = presentationCompiler
      .inlineValue(
        CompilerOffsetParams(
          URI.create(filename),
          code,
          offset,
          cancelToken
        )
      )
      .get()
    result.asScala.toList
  }

}
