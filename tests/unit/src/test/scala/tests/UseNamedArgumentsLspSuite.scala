package tests

import scala.meta.internal.metals.Refactorings.UseNamedArguments

object UseNamedArgumentsLspSuite
    extends BaseCodeActionLspSuite("useNamedArguments") {

  check(
    "basic",
    """|package a
       |
       |object A {
       |  def doSomething(foo: Int, bar: String): Boolean = true
       |  val x = doSomething(123,@@ "hello")
       |}
       |""".stripMargin,
    UseNamedArguments.title,
    """|package a
       |
       |object A {
       |  def doSomething(foo: Int, bar: String): Boolean = true
       |  val x = doSomething(foo = 123, bar = "hello")
       |}
       |""".stripMargin
  )

  check(
    "cursor-in-function-name",
    """|package a
       |
       |object A {
       |  def doSomething(foo: Int, bar: String): Boolean = true
       |  val x = doSomethi@@ng(123, "hello")
       |}
       |""".stripMargin,
    UseNamedArguments.title,
    """|package a
       |
       |object A {
       |  def doSomething(foo: Int, bar: String): Boolean = true
       |  val x = doSomething(foo = 123, bar = "hello")
       |}
       |""".stripMargin
  )

  check(
    "different-class",
    """|package a
       |
       |class Doer(a: Boolean) {
       |  def doSomething(foo: Int, bar: String): Boolean = true
       |}
       |
       |object A {
       |  val doer = new Doer(true)
       |  val x = doer.doSomething(123,@@ "hello")
       |}
       |""".stripMargin,
    UseNamedArguments.title,
    """|package a
       |
       |class Doer(a: Boolean) {
       |  def doSomething(foo: Int, bar: String): Boolean = true
       |}
       |
       |object A {
       |  val doer = new Doer(true)
       |  val x = doer.doSomething(foo = 123, bar = "hello")
       |}
       |""".stripMargin
  )

  check(
    "scala-Predef",
    """|package a
       |
       |object A {
       |  requi@@re(1 == 1)
       |}
       |""".stripMargin,
    UseNamedArguments.title,
    """|package a
       |
       |object A {
       |  require(requirement = 1 == 1)
       |}
       |""".stripMargin
  )

  check(
    "scala-stdlib",
    """|package a
       |
       |import scala.io.Source
       |
       |object A {
       |  val source = Source.fromString(@@"hello")
       |}
       |""".stripMargin,
    UseNamedArguments.title,
    """|package a
       |
       |import scala.io.Source
       |
       |object A {
       |  val source = Source.fromString(s = "hello")
       |}
       |""".stripMargin
  )

  check(
    "partially-named",
    """|package a
       |
       |object A {
       |  def doSomething(foo: Int, bar: String): Boolean = true
       |  val x = doSomething(foo = 123,@@ "hello")
       |}
       |""".stripMargin,
    UseNamedArguments.title,
    """|package a
       |
       |object A {
       |  def doSomething(foo: Int, bar: String): Boolean = true
       |  val x = doSomething(foo = 123, bar = "hello")
       |}
       |""".stripMargin
  )

  check(
    "nested",
    """|package a
       |
       |object A {
       |  def doSomething(foo: Int, bar: String): Boolean = true
       |  def addOne(n: Int): Int = n + 1
       |  val x = doSomething(addOn@@e(123), "hello")
       |}
       |""".stripMargin,
    UseNamedArguments.title,
    """|package a
       |
       |object A {
       |  def doSomething(foo: Int, bar: String): Boolean = true
       |  def addOne(n: Int): Int = n + 1
       |  val x = doSomething(addOne(n = 123), "hello")
       |}
       |""".stripMargin
  )

  check(
    "chained",
    """|package a
       |
       |class Doer(a: Boolean) {
       |  def doSomething(foo: Int, bar: String): Boolean = true
       |}
       |
       |object A {
       |  def createDoer(z: Boolean): Doer = new Doer(z)
       |  val x = createDoer(true).doSomething(@@123, "hello")
       |}
       |""".stripMargin,
    UseNamedArguments.title,
    """|package a
       |
       |class Doer(a: Boolean) {
       |  def doSomething(foo: Int, bar: String): Boolean = true
       |}
       |
       |object A {
       |  def createDoer(z: Boolean): Doer = new Doer(z)
       |  val x = createDoer(true).doSomething(foo = 123, bar = "hello")
       |}
       |""".stripMargin
  )

  check(
    "overload",
    """|package a
       |
       |object A {
       |  def doSomething(foo: Int, bar: String): Boolean = true
       |  def doSomething(a: Int, b: String, c: Boolean): Boolean = true
       |  val x = doSomething(@@123, "hello", false)
       |}
       |""".stripMargin,
    UseNamedArguments.title,
    """|package a
       |
       |object A {
       |  def doSomething(foo: Int, bar: String): Boolean = true
       |  def doSomething(a: Int, b: String, c: Boolean): Boolean = true
       |  val x = doSomething(a = 123, b = "hello", c = false)
       |}
       |""".stripMargin
  )

  check(
    "constructor",
    """|package a
       |
       |class Thing(foo: Int, bar: String)
       |
       |object A {
       |  val x = new Thing(123,@@ "hello")
       |}
       |""".stripMargin,
    UseNamedArguments.title,
    """|package a
       |
       |class Thing(foo: Int, bar: String)
       |
       |object A {
       |  val x = new Thing(foo = 123, bar = "hello")
       |}
       |""".stripMargin
  )

  check(
    "polymorphic",
    """|package a
       |
       |object A {
       |  def doSomething[A](foo: Int, bar: A): Boolean = true
       |  val x = doSomething[String](123,@@ "hello")
       |}
       |""".stripMargin,
    UseNamedArguments.title,
    """|package a
       |
       |object A {
       |  def doSomething[A](foo: Int, bar: A): Boolean = true
       |  val x = doSomething[String](foo = 123, bar = "hello")
       |}
       |""".stripMargin
  )

  check(
    "polymorphic-constructor",
    """|package a
       |
       |class Thing[A](foo: Int, bar: A)
       |
       |object A {
       |  val x = new Thing[String](123,@@ "hello")
       |}
       |""".stripMargin,
    UseNamedArguments.title,
    """|package a
       |
       |class Thing[A](foo: Int, bar: A)
       |
       |object A {
       |  val x = new Thing[String](foo = 123, bar = "hello")
       |}
       |""".stripMargin
  )

  check(
    "case-class-apply",
    """|package a
       |
       |case class Thing(foo: Int, bar: String)
       |
       |object A {
       |  val x = Thing(123,@@ "hello")
       |}
       |""".stripMargin,
    UseNamedArguments.title,
    """|package a
       |
       |case class Thing(foo: Int, bar: String)
       |
       |object A {
       |  val x = Thing(foo = 123, bar = "hello")
       |}
       |""".stripMargin
  )

}
