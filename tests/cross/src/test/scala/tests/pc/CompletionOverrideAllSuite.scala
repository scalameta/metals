package tests.pc

import tests.BaseCompletionSuite
import funsuite.BeforeAll

class CompletionOverrideAllSuite extends BaseCompletionSuite {

  override def beforeAll(context: BeforeAll): Unit = {
    indexJDK()
  }

  check(
    "simple",
    """|package example
       |
       |trait Foo {
       |  def foo: Int
       |  def bar: Int
       |}
       |object Main {
       |  val x = new Foo {
       |    def@@
       |  }
       |}
       |""".stripMargin,
    """|Implement all members (2 total)
       |""".stripMargin,
    filter = _.contains("Implement")
  )

  checkEdit(
    "simple-edit",
    """|package example
       |
       |trait Foo {
       |  def foo: Int
       |  def bar: Int
       |}
       |object Main {
       |  val x = new Foo {
       |    def@@
       |  }
       |}
       |""".stripMargin,
    """|package example
       |
       |trait Foo {
       |  def foo: Int
       |  def bar: Int
       |}
       |object Main {
       |  val x = new Foo {
       |    def foo: Int = ${0:???}
       |    def bar: Int = ${0:???}
       |  }
       |}
       |""".stripMargin,
    filter = _.contains("Implement")
  )

  check(
    "simple-three",
    """|package example
       |
       |trait Foo {
       |  def foo: Int
       |  def bar: Int
       |  def car: Int
       |}
       |object Main {
       |  val x = new Foo {
       |    def@@
       |  }
       |}
       |""".stripMargin,
    """|Implement all members (3 total)
       |""".stripMargin,
    filter = _.contains("Implement")
  )

  checkEdit(
    "simple-three-edit",
    """|package example
       |
       |trait Foo {
       |  def foo: Int
       |  def bar: Int
       |  def car: Int
       |}
       |object Main {
       |  val x = new Foo {
       |    def@@
       |  }
       |}
       |""".stripMargin,
    """
      |package example
      |
      |trait Foo {
      |  def foo: Int
      |  def bar: Int
      |  def car: Int
      |}
      |object Main {
      |  val x = new Foo {
      |    def foo: Int = ${0:???}
      |    def bar: Int = ${0:???}
      |    def car: Int = ${0:???}
      |  }
      |}
      |""".stripMargin,
    filter = _.contains("Implement")
  )

  check(
    "two-left",
    """|package example
       |
       |trait Foo {
       |  def foo: Int
       |  def bar: Int
       |  def car: Int
       |}
       |object Main {
       |  val x = new Foo {
           def foo = 2
       |    def@@
       |  }
       |}
       |""".stripMargin,
    """|Implement all members (2 total)
       |""".stripMargin,
    filter = _.contains("Implement")
  )

  checkEdit(
    "two-left-edit",
    """|package example
       |
       |trait Foo {
       |  def foo: Int
       |  def bar: Int
       |  def car: Int
       |}
       |object Main {
       |  val x = new Foo {
       |    def foo = 2
       |    def@@
       |  }
       |}
       |""".stripMargin,
    """|package example
       |
       |trait Foo {
       |  def foo: Int
       |  def bar: Int
       |  def car: Int
       |}
       |object Main {
       |  val x = new Foo {
       |    def foo = 2
       |    def bar: Int = ${0:???}
       |    def car: Int = ${0:???}
       |  }
       |}
       |""".stripMargin,
    filter = _.contains("Implement")
  )

  check(
    "none-with-one",
    """|package example
       |
       |trait Foo {
       |  def foo: Int
       |}
       |object Main {
       |  val x = new Foo {
       |    def@@
       |  }
       |}
       |""".stripMargin,
    "",
    filter = _.contains("Implement")
  )

  check(
    "same-scope",
    """|package a { class Foo }
       |package b { class Foo }
       |
       |trait Foo {
       |  def one: a.Foo
       |  def two: b.Foo
       |}
       |object Main {
       |  val x = new Foo {
       |    def@@
       |  }
       |}
       |""".stripMargin,
    """|Implement all members (2 total)
       |""".stripMargin,
    filter = _.contains("Implement")
  )

  checkEdit(
    "same-scope-edit",
    """|package a { class Foo }
       |package b { class Foo }
       |
       |trait Foo {
       |  def one: a.Foo
       |  def two: b.Foo
       |}
       |object Main {
       |  val x = new Foo {
       |    def@@
       |  }
       |}
       |""".stripMargin,
    """|package a { class Foo }
       |package b { class Foo }
       |
       |trait Foo {
       |  def one: a.Foo
       |  def two: b.Foo
       |}
       |object Main {
       |  val x = new Foo {
       |    def one: a.Foo = ${0:???}
       |    def two: b.Foo = ${0:???}
       |  }
       |}
       |""".stripMargin,
    filter = _.contains("Implement")
  )

  check(
    "include-val-var",
    """|package example
       |
       |trait Foo {
       |  def foo: Int
       |  val bar: Int
       |  var car: Int
       |}
       |object Main {
       |  val x = new Foo {
       |    def@@
       |  }
       |}
       |""".stripMargin,
    """|Implement all members (3 total)
       |""".stripMargin,
    filter = _.contains("Implement")
  )

  checkEdit(
    "include-val-var-edit",
    """|package example
       |
       |trait Foo {
       |  def foo: Int
       |  val bar: Int
       |  var car: Int
       |}
       |object Main {
       |  val x = new Foo {
       |    def@@
       |  }
       |}
       |""".stripMargin,
    """|package example
       |
       |trait Foo {
       |  def foo: Int
       |  val bar: Int
       |  var car: Int
       |}
       |object Main {
       |  val x = new Foo {
       |    def foo: Int = ${0:???}
       |    val bar: Int = ${0:???}
       |    var car: Int = ${0:???}
       |  }
       |}
       |""".stripMargin,
    filter = _.contains("Implement")
  )

  check(
    "mixed-partial",
    """|package example
       |
       |trait Foo {
       |  def foo: Int
       |  def foo2: Int
       |  var bar: Int
       |  var bar2: Int
       |  val car: Int
       |  val car2: Int
       |}
       |object Main {
       |  val x = new Foo {
       |   def foo: int = 3
       |   var bar: int = 2
       |   val car: Int = 1
       |   def@@
       |  }
       |}
       |""".stripMargin,
    """|Implement all members (3 total)
       |""".stripMargin,
    filter = _.contains("Implement")
  )

  checkEdit(
    "mixed-partial-edit",
    """|package example
       |
       |trait Foo {
       |  def foo: Int
       |  def foo2: Int
       |  var bar: Int
       |  var bar2: Int
       |  val car: Int
       |  val car2: Int
       |}
       |object Main {
       |  val x = new Foo {
       |    def foo: int = 3
       |    var bar: int = 2
       |    val car: Int = 1
       |    def@@
       |  }
       |}
       |""".stripMargin,
    """|package example
       |
       |trait Foo {
       |  def foo: Int
       |  def foo2: Int
       |  var bar: Int
       |  var bar2: Int
       |  val car: Int
       |  val car2: Int
       |}
       |object Main {
       |  val x = new Foo {
       |    def foo: int = 3
       |    var bar: int = 2
       |    val car: Int = 1
       |    def foo2: Int = ${0:???}
       |    var bar2: Int = ${0:???}
       |    val car2: Int = ${0:???}
       |  }
       |}
       |""".stripMargin,
    filter = _.contains("Implement")
  )
}
