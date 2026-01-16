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
       |""".stripMargin
  )

  check(
    "basic-apply",
    """|object A {
       |  <<Future>>(2)
       |}
       |""".stripMargin,
    """|scala.concurrent
       |""".stripMargin,
    compat = Map(
      "2.11" ->
        """|scala.concurrent
           |scala.concurrent.impl
           |""".stripMargin
    )
  )

  check(
    "basic-function-apply",
    """|
       |object ForgeFor{
       |  def importMe(): Int = ???
       |}
       |object ForgeFor2{
       |  case class importMe()
       |}
       |
       |
       |object test2 {
       |  <<importMe>>()
       |}
       |""".stripMargin,
    """|ForgeFor
       |ForgeFor2
       |""".stripMargin
  )

  check(
    "basic-apply-wrong",
    """|object A {
       |  new <<Future>>(2)
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

  check(
    "basic-fuzzy",
    """|object A {
       |  <<Future>>.thisMethodDoesntExist(2)
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

  check(
    "typed-simple",
    """|object A {
       |  import scala.concurrent.Promise
       |  val fut: <<Future>> = Promise[Unit]().future
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
    "interpolator-edit-scala2",
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
  check(
    "type-vs-object",
    """|package a
       |
       |object Thing1 {
       |  class Foo
       |}
       |
       |object Thing2 {
       |  object Foo
       |}
       |
       |class Baz {
       |  def x: <<Foo>> = x
       |}
       |""".stripMargin,
    """|a.Thing1
       |""".stripMargin
  )

  check(
    "type-vs-object2",
    """|package a
       |
       |object Thing1 {
       |  class Foo
       |}
       |
       |object Thing2 {
       |  object Foo
       |}
       |
       |class Baz {
       |  def x: List[<<Foo>>] = x
       |}
       |""".stripMargin,
    """|a.Thing1
       |""".stripMargin
  )

  check(
    "type-vs-object3",
    """|package a
       |
       |object Thing1 {
       |  class Foo
       |}
       |
       |object Thing2 {
       |  object Foo
       |}
       |
       |class Baz {
       |  def x: <<Foo>>.type = x
       |}
       |
       |""".stripMargin,
    """|a.Thing2
       |""".stripMargin
  )

  check(
    "type-vs-object3",
    """|package a
       |
       |object Thing1 {
       |  class Foo
       |}
       |
       |object Thing2 {
       |  object Foo
       |}
       |
       |class Baz {
       |  def x: List[Option[<<Foo>>]] = x
       |}
       |""".stripMargin,
    """|a.Thing1
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

  // see: https://github.com/scalameta/metals/issues/8061
  checkEdit(
    "static-object-member",
    """|object A {
       |  <<Breaks>>.break()
       |}
       |""".stripMargin,
    """|import scala.util.control.Breaks
       |object A {
       |  Breaks.break()
       |}
       |""".stripMargin
  )

}
