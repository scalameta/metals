package tests.pc

import munit.Location
import tests.BaseAutoImportsSuite

class AutoImportsSuite extends BaseAutoImportsSuite {

  check(
    "basic",
    """|object A {
       |  <<Future>>.successful(2)
       |}
       |""".stripMargin,
    """|scala.concurrent
       |java.util.concurrent
       |""".stripMargin,
    compat = Map(
      "2.11" ->
        """|scala.concurrent
           |scala.concurrent.impl
           |java.util.concurrent
           |""".stripMargin
    )
  )

  checkEdit(
    "basic-edit",
    """|package a
       |
       |object A {
       |  <<Future>>.successful(2)
       |}
       |""".stripMargin,
    """|package a
       |
       |import scala.concurrent.Future
       |
       |object A {
       |  Future.successful(2)
       |}
       |""".stripMargin
  )

  checkEdit(
    "basic-edit-comment",
    """|/**
       | * @param code
       | * @return
       |*/
       |object A {
       |  <<Future>>.successful(2)
       |}
       |""".stripMargin,
    """|import scala.concurrent.Future
       |/**
       | * @param code
       | * @return
       |*/
       |object A {
       |  Future.successful(2)
       |}
       |""".stripMargin
  )

  checkEdit(
    "basic-edit-directive",
    """|// using scala 35
       |// using something else
       |
       |object A {
       |  <<Future>>.successful(2)
       |}
       |""".stripMargin,
    """|// using scala 35
       |// using something else
       |import scala.concurrent.Future
       |
       |object A {
       |  Future.successful(2)
       |}
       |""".stripMargin
  )

  checkEdit(
    "scala-cli-sc-using-directives",
    """|object main {
       |/*<script>*///> using scala "3.1.3"
       |
       |object x {
       |  <<Try>>("1".toString)
       |}
       |}
       |
       |""".stripMargin,
    """|object main {
       |/*<script>*///> using scala "3.1.3"
       |import scala.util.Try
       |
       |object x {
       |  Try("1".toString)
       |}
       |}
       |""".stripMargin,
    filename = "A.sc.scala"
  )

  checkEdit(
    "symbol-no-prefix",
    """|package a
       |
       |object A {
       |  val uuid = <<UUID>>.randomUUID()
       |}
       |""".stripMargin,
    """|package a
       |
       |import java.util.UUID
       |
       |object A {
       |  val uuid = UUID.randomUUID()
       |}
       |""".stripMargin
  )

  checkEdit(
    "symbol-prefix-existing",
    """|package a
       |
       |object A {
       |  val uuid = <<UUID>>.randomUUID()
       |}
       |""".stripMargin,
    """|package a
       |
       |import java.util.UUID
       |
       |object A {
       |  val uuid = UUID.randomUUID()
       |}
       |""".stripMargin
  )

  checkEdit(
    "symbol-prefix",
    """|package a
       |
       |object A {
       |  val l : <<Map>>[String, Int] = ???
       |}
       |""".stripMargin,
    """|package a
       |
       |import java.{util => ju}
       |
       |object A {
       |  val l : ju.Map[String, Int] = ???
       |}
       |""".stripMargin
  )

  checkEdit(
    "interpolator-edit-scala2".tag(IgnoreScala3),
    """|package a
       |
       |object A {
       |  val l = s"${<<Seq>>(2)}"
       |}
       |""".stripMargin,
    """|package a
       |
       |import scala.collection.mutable
       |
       |object A {
       |  val l = s"${mutable.Seq(2)}"
       |}
       |""".stripMargin,
    selection = 1
  )

  checkEdit(
    "interpolator-edit-scala3".tag(IgnoreScala2),
    """|package a
       |
       |object A {
       |  val l = s"${<<Seq>>(2)}"
       |}
       |""".stripMargin,
    """|package a
       |
       |import scala.collection.mutable
       |
       |object A {
       |  val l = s"${mutable.Seq(2)}"
       |}
       |""".stripMargin
  )

  checkEdit(
    "package-object",
    """|
       |package object metals{
       |  object ABC
       |}
       |object Main{
       | val obj = <<ABC>>
       |}
       |""".stripMargin,
    """|import metals.ABC
       |
       |package object metals{
       |  object ABC
       |}
       |object Main{
       | val obj = ABC
       |}
       |""".stripMargin
  )

  checkEdit(
    "import-inside-package-object",
    """|package a
       |
       |package object b {
       |  val l = s"${<<ListBuffer>>(2)}"
       |}
       |""".stripMargin,
    """|package a
       |
       |import scala.collection.mutable.ListBuffer
       |
       |package object b {
       |  val l = s"${ListBuffer(2)}"
       |}
       |""".stripMargin
  )

  checkEdit(
    "multiple-packages",
    """|package a
       |package b
       |package c
       |
       |object A {
       |  val l = s"${<<ListBuffer>>(2)}"
       |}
       |""".stripMargin,
    """|package a
       |package b
       |package c
       |
       |import scala.collection.mutable.ListBuffer
       |
       |object A {
       |  val l = s"${ListBuffer(2)}"
       |}
       |""".stripMargin
  )

  // Note: Simple imports are appended after existing imports
  checkEdit(
    "multiple-packages-existing-imports",
    """|package a
       |package b
       |package c
       |
       |import scala.concurrent.Future
       |
       |object A {
       |  val l = s"${<<ListBuffer>>(2)}"
       |}
       |""".stripMargin,
    """|package a
       |package b
       |package c
       |
       |import scala.concurrent.Future
       |import scala.collection.mutable.ListBuffer
       |
       |object A {
       |  val l = s"${ListBuffer(2)}"
       |}
       |""".stripMargin
  )

  checkEdit(
    "import-in-import",
    """|package inimport
       |
       |object A {
       |  import <<ExecutionContext>>.global
       |}
       |""".stripMargin,
    """|package inimport
       |
       |object A {
       |  import scala.concurrent.ExecutionContext.global
       |}
       |""".stripMargin
  )

  // Tests for intelligent import placement (appended within section)
  checkEdit(
    "intelligent-placement-append",
    """|package a
       |
       |import java.util.concurrent.TimeUnit
       |import scala.collection.mutable.Set
       |
       |object A {
       |  val l = <<ListBuffer>>(2)
       |}
       |""".stripMargin,
    """|package a
       |
       |import java.util.concurrent.TimeUnit
       |import scala.collection.mutable.Set
       |import scala.collection.mutable.ListBuffer
       |
       |object A {
       |  val l = ListBuffer(2)
       |}
       |""".stripMargin
  )

  // ===== GROUPED IMPORTS: SINGLE-LINE =====

  // Test inserting FIRST in single-line group: {A, B} -> {NewFirst, A, B}
  checkEdit(
    "grouped-single-line-first",
    """|package a
       |
       |import scala.collection.mutable.{Buffer, Set}
       |
       |object A {
       |  val l = <<ArrayBuffer>>(2)
       |}
       |""".stripMargin,
    """|package a
       |
       |import scala.collection.mutable.{ArrayBuffer, Buffer, Set}
       |
       |object A {
       |  val l = ArrayBuffer(2)
       |}
       |""".stripMargin
  )

  // Test inserting MIDDLE in single-line group: {A, C} -> {A, B, C}
  checkEdit(
    "grouped-single-line-middle",
    """|package a
       |
       |import scala.collection.mutable.{Buffer, Set}
       |
       |object A {
       |  val l = <<ListBuffer>>(2)
       |}
       |""".stripMargin,
    """|package a
       |
       |import scala.collection.mutable.{Buffer, ListBuffer, Set}
       |
       |object A {
       |  val l = ListBuffer(2)
       |}
       |""".stripMargin
  )

  // Test inserting LAST in single-line group: {A, B} -> {A, B, C}
  checkEdit(
    "grouped-single-line-last",
    """|package a
       |
       |import scala.collection.mutable.{ArrayBuffer, Buffer}
       |
       |object A {
       |  val l = <<ListBuffer>>(2)
       |}
       |""".stripMargin,
    """|package a
       |
       |import scala.collection.mutable.{ArrayBuffer, Buffer, ListBuffer}
       |
       |object A {
       |  val l = ListBuffer(2)
       |}
       |""".stripMargin
  )

  // ===== GROUPED IMPORTS: MULTILINE =====

  // Test inserting FIRST in multiline group
  checkEdit(
    "grouped-multiline-first",
    """|package a
       |
       |import scala.collection.mutable.{
       |  Buffer,
       |  Set
       |}
       |
       |object A {
       |  val l = <<ArrayBuffer>>(2)
       |}
       |""".stripMargin,
    """|package a
       |
       |import scala.collection.mutable.{
       |  ArrayBuffer,
       |  Buffer,
       |  Set
       |}
       |
       |object A {
       |  val l = ArrayBuffer(2)
       |}
       |""".stripMargin
  )

  // Test inserting MIDDLE in multiline group
  checkEdit(
    "grouped-multiline-middle",
    """|package a
       |
       |import scala.collection.mutable.{
       |  Buffer,
       |  Set
       |}
       |
       |object A {
       |  val l = <<ListBuffer>>(2)
       |}
       |""".stripMargin,
    """|package a
       |
       |import scala.collection.mutable.{
       |  Buffer,
       |  ListBuffer,
       |  Set
       |}
       |
       |object A {
       |  val l = ListBuffer(2)
       |}
       |""".stripMargin
  )

  // Test inserting LAST in multiline group (handles comma insertion)
  checkEdit(
    "grouped-multiline-last",
    """|package a
       |
       |import scala.collection.mutable.{
       |  ArrayBuffer,
       |  Buffer
       |}
       |
       |object A {
       |  val l = <<ListBuffer>>(2)
       |}
       |""".stripMargin,
    """|package a
       |
       |import scala.collection.mutable.{
       |  ArrayBuffer,
       |  Buffer,
       |  ListBuffer
       |}
       |
       |object A {
       |  val l = ListBuffer(2)
       |}
       |""".stripMargin
  )

  // ===== BLANK-LINE SEPARATED SECTIONS =====

  // Test picking the java section when importing java.* (appends to section)
  checkEdit(
    "section-java",
    """|package a
       |
       |import java.util.concurrent.TimeUnit
       |
       |import scala.concurrent.Future
       |import scala.collection.mutable.Buffer
       |
       |object A {
       |  val uuid = <<UUID>>.randomUUID()
       |}
       |""".stripMargin,
    """|package a
       |
       |import java.util.concurrent.TimeUnit
       |import java.util.UUID
       |
       |import scala.concurrent.Future
       |import scala.collection.mutable.Buffer
       |
       |object A {
       |  val uuid = UUID.randomUUID()
       |}
       |""".stripMargin
  )

  // Test picking the scala section when importing scala.* (appends to section)
  checkEdit(
    "section-scala",
    """|package a
       |
       |import java.util.UUID
       |
       |import scala.concurrent.Future
       |import scala.collection.mutable.Buffer
       |
       |object A {
       |  val l = <<ListBuffer>>(2)
       |}
       |""".stripMargin,
    """|package a
       |
       |import java.util.UUID
       |
       |import scala.concurrent.Future
       |import scala.collection.mutable.Buffer
       |import scala.collection.mutable.ListBuffer
       |
       |object A {
       |  val l = ListBuffer(2)
       |}
       |""".stripMargin
  )

  // Test with sections and grouped imports - add to existing group
  checkEdit(
    "section-with-grouped",
    """|package a
       |
       |import java.util.{List, Map}
       |
       |import scala.concurrent.Future
       |import scala.collection.mutable.{Buffer, Set}
       |
       |object A {
       |  val l = <<ListBuffer>>(2)
       |}
       |""".stripMargin,
    """|package a
       |
       |import java.util.{List, Map}
       |
       |import scala.concurrent.Future
       |import scala.collection.mutable.{Buffer, ListBuffer, Set}
       |
       |object A {
       |  val l = ListBuffer(2)
       |}
       |""".stripMargin
  )

  // Bug: javax import should NOT be placed after scala imports
  // When no matching section exists, it should be placed in a sensible location
  checkEdit(
    "different-toplevel-package",
    """|package a
       |
       |import scala.jdk.CollectionConverters._
       |import scala.util.Failure
       |import scala.util.Success
       |import scala.util.control.NonFatal
       |
       |object A {
       |  val x = <<ObjectName>>("test:type=Test")
       |}
       |""".stripMargin,
    """|package a
       |
       |import javax.management.ObjectName
       |import scala.jdk.CollectionConverters._
       |import scala.util.Failure
       |import scala.util.Success
       |import scala.util.control.NonFatal
       |
       |object A {
       |  val x = ObjectName("test:type=Test")
       |}
       |""".stripMargin
  )

  // Bug: scala.* should be placed alphabetically (after org.*), not at the top
  checkEdit(
    "no-prefix-match-alphabetical",
    """|package a
       |
       |import com.example.Util
       |import java.util.UUID
       |import org.apache.logging.Logger
       |
       |object A {
       |  val r = <<Random>>.nextInt()
       |}
       |""".stripMargin,
    """|package a
       |
       |import com.example.Util
       |import java.util.UUID
       |import org.apache.logging.Logger
       |import scala.util.Random
       |
       |object A {
       |  val r = Random.nextInt()
       |}
       |""".stripMargin
  )

  // Bug: java.util.UUID should be placed near java.util.Random, not at the end
  checkEdit(
    "same-prefix-different-section",
    """|package a
       |
       |import com.example.Util
       |import com.example.client.{
       |  ClientA,
       |  ClientB,
       |  ClientC
       |}
       |import java.util.Random
       |import org.apache.logging.Logger
       |
       |object A {
       |  val uuid = <<UUID>>.randomUUID()
       |}
       |""".stripMargin,
    """|package a
       |
       |import com.example.Util
       |import com.example.client.{
       |  ClientA,
       |  ClientB,
       |  ClientC
       |}
       |import java.util.Random
       |import java.util.UUID
       |import org.apache.logging.Logger
       |
       |object A {
       |  val uuid = UUID.randomUUID()
       |}
       |""".stripMargin
  )

  // ===== GROUPED IMPORTS: EDGE CASES =====

  // Test with trailing comma style (common in some codebases)
  checkEdit(
    "grouped-multiline-trailing-comma",
    """|package a
       |
       |import scala.collection.mutable.{
       |  Buffer,
       |  Set,
       |}
       |
       |object A {
       |  val l = <<ListBuffer>>(2)
       |}
       |""".stripMargin,
    """|package a
       |
       |import scala.collection.mutable.{
       |  Buffer,
       |  ListBuffer,
       |  Set,
       |}
       |
       |object A {
       |  val l = ListBuffer(2)
       |}
       |""".stripMargin
  )

  checkAmmoniteEdit(
    "first-auto-import-amm-script",
    ammoniteWrapper(
      """|
         |val p: <<Path>> = ???
         |""".stripMargin
    ),
    ammoniteWrapper(
      """|import java.nio.file.Path
         |
         |val p: Path = ???
         |""".stripMargin
    )
  )

  checkAmmoniteEdit(
    "second-auto-import-amm-script",
    ammoniteWrapper(
      """import java.nio.file.Files
        |val p: <<Path>> = ???
        |""".stripMargin
    ),
    ammoniteWrapper(
      """import java.nio.file.Files
        |import java.nio.file.Path
        |val p: Path = ???
        |""".stripMargin
    )
  )

  checkAmmoniteEdit(
    "amm-objects",
    ammoniteWrapper(
      """|
         |object a {
         |  object b {
         |    val p: <<Path>> = ???
         |  }
         |}
         |""".stripMargin
    ),
    ammoniteWrapper(
      """|import java.nio.file.Path
         |
         |object a {
         |  object b {
         |    val p: Path = ???
         |  }
         |}
         |""".stripMargin
    )
  )

  checkAmmoniteEdit(
    "first-auto-import-amm-script-with-header",
    ammoniteWrapper(
      """|// scala 2.13.1
         |
         |val p: <<Path>> = ???
         |""".stripMargin
    ),
    ammoniteWrapper(
      """|// scala 2.13.1
         |import java.nio.file.Path
         |
         |val p: Path = ???
         |""".stripMargin
    )
  )

  checkEdit(
    "i6477".tag(IgnoreScala2),
    """|package a
       |import a.b.SomeClass as SC
       |
       |package b {
       |  class SomeClass
       |}
       |package c {
       |  class SomeClass
       |}
       |
       |val bar: SC = ???
       |val foo: <<SomeClass>> = ???
       |""".stripMargin,
    """|package a
       |import a.b.SomeClass as SC
       |import a.c.SomeClass
       |
       |package b {
       |  class SomeClass
       |}
       |package c {
       |  class SomeClass
       |}
       |
       |val bar: SC = ???
       |val foo: SomeClass = ???
       |""".stripMargin
  )

  checkEdit(
    "use-packages-in-scope".tag(IgnoreScala2),
    """|import scala.collection.mutable as mut
       |
       |val l = <<ListBuffer>>(2)
       |""".stripMargin,
    """|import scala.collection.mutable as mut
       |import mut.ListBuffer
       |
       |val l = ListBuffer(2)
       |""".stripMargin
  )

  private def ammoniteWrapper(code: String): String =
    // Vaguely looks like a scala file that Ammonite generates
    // from a sc file.
    // Just not referencing any Ammonite class, that we don't pull
    // in the tests here.
    s"""|package ammonite
        |package $$file.`auto-import`
        |import _root_.scala.collection.mutable.{
        |  HashMap => MutableHashMap
        |}
        |
        |object test{
        |/*<start>*/
        |$code
        |}
        |""".stripMargin

  // https://dotty.epfl.ch/docs/internals/syntax.html#soft-keywords
  List("infix", "inline", "opaque", "open", "transparent", "as", "derives",
    "end", "extension", "throws", "using").foreach(softKeywordCheck)

  private def softKeywordCheck(keyword: String)(implicit loc: Location) =
    checkEdit(
      s"'$keyword'-named-object".tag(IgnoreScala2),
      s"""|
          |object $keyword{ object ABC }
          |object Main{ val obj = <<ABC>> }
          |""".stripMargin,
      s"""|import $keyword.ABC
          |
          |object $keyword{ object ABC }
          |object Main{ val obj = ABC }
          |""".stripMargin
    )

}
