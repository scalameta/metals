package tests.pc

import tests.BaseCompletionSuite

class CompletionExtensionMethodSuite extends BaseCompletionSuite {

  check(
    "simple".tag(IgnoreScala2),
    """|package example
       |
       |object enrichments:
       |  extension (num: Int)
       |    def incr: Int = num + 1
       |
       |def main = 100.inc@@
       |""".stripMargin,
    """|incr: Int (extension)
       |""".stripMargin
  )

  check(
    "simple-old-syntax",
    """|package example
       |
       |object Test {
       |  implicit class TestOps(a: Int) {
       |    def testOps(b: Int): String = ???
       | }
       |}
       |
       |object O{
       |  def main = 100.test@@
       |}
       |""".stripMargin,
    """|testOps(b: Int): String (implicit)
       |""".stripMargin,
    filter = _.contains("(implicit)")
  )

  check(
    "simple2".tag(IgnoreScala2),
    """|package example
       |
       |object enrichments:
       |  extension (num: Int)
       |    def incr: Int = num + 1
       |
       |def main = 100.i@@
       |""".stripMargin,
    """|incr: Int (extension)
       |""".stripMargin,
    filter = _.contains("(extension)")
  )

  check(
    "simple2-old-syntax",
    """|package example
       |
       |object enrichments {
       |  implicit class TestOps(a: Int) {
       |    def testOps(b: Int): String = ???
       |  }
       |}
       |
       |object O {
       |  def main = 100.t@@
       |}
       |""".stripMargin,
    """|testOps(b: Int): String (implicit)
       |""".stripMargin,
    filter = _.contains("(implicit)")
  )

  check(
    "simple-empty".tag(IgnoreScala2),
    """|package example
       |
       |object enrichments:
       |  extension (num: Int)
       |    def incr: Int = num + 1
       |
       |def main = 100.@@
       |""".stripMargin,
    """|incr: Int (extension)
       |""".stripMargin,
    filter = _.contains("(extension)")
  )

  check(
    "simple-empty-old",
    """|package example
       |
       |object enrichments {
       |  implicit class TestOps(a: Int) {
       |    def testOps(b: Int): String = ???
       |  }
       |}
       |
       |object O {
       |  def main = 100.@@
       |}
       |""".stripMargin,
    """|testOps(b: Int): String (implicit)
       |""".stripMargin,
    filter = _.contains("(implicit)")
  )

  check(
    "filter-by-type".tag(IgnoreScala2),
    """|package example
       |
       |object enrichments:
       |  extension (num: Int)
       |    def incr: Int = num + 1
       |  extension (str: String)
       |    def identity: String = str
       |
       |def main = "foo".i@@
       |""".stripMargin,
    """|identity: String (extension)
       |""".stripMargin, // incr won't be available
    filter = _.contains("(extension)")
  )

  check(
    "filter-by-type-old",
    """|package example
       |
       |object enrichments {
       |  implicit class A(num: Int) {
       |    def identity2: Int = num + 1
       |  }
       |  implicit class B(str: String) {
       |    def identity: String = str
       |  }
       |}
       |
       |object O {
       |  def main = "foo".iden@@
       |}
       |""".stripMargin,
    """|identity: String (implicit)
       |""".stripMargin, // identity2 won't be available
    filter = _.contains("(implicit)")
  )

  check(
    "filter-by-type-subtype".tag(IgnoreScala2),
    """|package example
       |
       |class A
       |class B extends A
       |
       |object enrichments:
       |  extension (a: A)
       |    def doSomething: A = a
       |
       |def main = (new B).do@@
       |""".stripMargin,
    """|doSomething: A (extension)
       |""".stripMargin,
    filter = _.contains("(extension)")
  )

  check(
    "filter-by-type-subtype-old",
    """|package example
       |
       |class A
       |class B extends A
       |
       |object enrichments {
       |  implicit class Test(a: A) {
       |    def doSomething: A = a
       | }
       |}
       |
       |object O {
       |  def main = (new B).do@@
       |}
       |""".stripMargin,
    """|doSomething: A (implicit)
       |""".stripMargin,
    filter = _.contains("(implicit)")
  )

  checkEdit(
    "simple-edit".tag(IgnoreScala2),
    """|package example
       |
       |object enrichments:
       |  extension (num: Int)
       |    def incr: Int = num + 1
       |
       |def main = 100.inc@@
       |""".stripMargin,
    """|package example
       |
       |import example.enrichments.incr
       |
       |object enrichments:
       |  extension (num: Int)
       |    def incr: Int = num + 1
       |
       |def main = 100.incr
       |""".stripMargin
  )

  checkEdit(
    "simple-edit-old",
    """|package example
       |
       |object enrichments {
       |  implicit class A (num: Int) {
       |    def incr: Int = num + 1
       |  }
       |}
       |
       |object O {
       |  def main = 100.inc@@
       |}
       |""".stripMargin,
    """|package example
       |
       |import example.enrichments.A
       |
       |object enrichments {
       |  implicit class A (num: Int) {
       |    def incr: Int = num + 1
       |  }
       |}
       |
       |object O {
       |  def main = 100.incr
       |}
       |""".stripMargin,
    filter = _.contains("(implicit)")
  )

  checkEdit(
    "simple-edit-suffix".tag(IgnoreScala2),
    """|package example
       |
       |object enrichments:
       |  extension (num: Int)
       |    def plus(other: Int): Int = num + other
       |
       |def main = 100.pl@@
       |""".stripMargin,
    """|package example
       |
       |import example.enrichments.plus
       |
       |object enrichments:
       |  extension (num: Int)
       |    def plus(other: Int): Int = num + other
       |
       |def main = 100.plus($0)
       |""".stripMargin
  )

  checkEdit(
    "name-conflict".tag(IgnoreScala2),
    """|package example
       |
       |import example.enrichments.*
       |
       |object enrichments:
       |  extension (num: Int)
       |    def plus(other: Int): Int = num + other
       |
       |def main = {
       |  val plus = 100.plus(19)
       |  val y = 19.pl@@
       |}
       |""".stripMargin,
    """|package example
       |
       |import example.enrichments.*
       |
       |object enrichments:
       |  extension (num: Int)
       |    def plus(other: Int): Int = num + other
       |
       |def main = {
       |  val plus = 100.plus(19)
       |  val y = 19.plus($0)
       |}
       """.stripMargin
  )

  checkEdit(
    "simple-edit-suffix-old",
    """|package example
       |
       |object enrichments {
       |  implicit class A (val num: Int) {
       |    def plus(other: Int): Int = num + other
       |  }
       |}
       |
       |object O {
       |  def main = 100.pl@@
       |}
       |""".stripMargin,
    """|package example
       |
       |import example.enrichments.A
       |
       |object enrichments {
       |  implicit class A (val num: Int) {
       |    def plus(other: Int): Int = num + other
       |  }
       |}
       |
       |object O {
       |  def main = 100.plus($0)
       |}
       |""".stripMargin
  )

  // NOTE: In 3.1.3, package object name includes the whole path to file
  // eg. in 3.2.2 we get `A$package`, but in 3.1.3 `/some/path/to/file/A$package`
  check(
    "directly-in-pkg1".tag(
      IgnoreScalaVersion.forLessThan("3.2.2")
    ),
    """|
       |package example:
       |  extension (num: Int)
       |    def incr: Int = num + 1
       |
       |package example2:
       |  def main = 100.inc@@
       |""".stripMargin,
    """|incr: Int (extension)
       |""".stripMargin
  )

  check(
    "directly-in-pkg1-old"
      .tag(
        IgnoreScalaVersion.forLessThan("3.2.2")
      ),
    """|
       |package examples {
       |  implicit class A(num: Int) {
       |    def incr: Int = num + 1
       |  }
       |}
       |
       |package examples2 {
       |  def main = 100.inc@@
       |}
       |""".stripMargin,
    """|incr: Int (implicit)
       |""".stripMargin
  )

  check(
    "directly-in-pkg2".tag(
      IgnoreScalaVersion.forLessThan("3.2.2")
    ),
    """|package example:
       |  object X:
       |    def fooBar(num: Int) = num + 1
       |  extension (num: Int) def incr: Int = num + 1
       |
       |package example2:
       |  def main = 100.inc@@
       |""".stripMargin,
    """|incr: Int (extension)
       |""".stripMargin
  )

  check(
    "directly-in-pkg2-old"
      .tag(
        IgnoreScalaVersion.for3LessThan("3.2.2")
      ),
    """|package examples {
       |  object X {
       |    def fooBar(num: Int) = num + 1
       |  }
       |  implicit class A (num: Int) { def incr: Int = num + 1 }
       |}
       |
       |package examples2 {
       |  object O {
       |    def main = 100.inc@@
       |  }
       |}
       |""".stripMargin,
    """|incr: Int (implicit)
       |""".stripMargin,
    filter = _.contains("(implicit)")
  )

  checkEdit(
    "directly-in-pkg3".tag(
      IgnoreScalaVersion.forLessThan("3.2.2")
    ),
    """|package example:
       |  extension (num: Int) def incr: Int = num + 1
       |
       |package example2:
       |  def main = 100.inc@@
       |""".stripMargin,
    """|import example.incr
       |package example:
       |  extension (num: Int) def incr: Int = num + 1
       |
       |package example2:
       |  def main = 100.incr
       |""".stripMargin
  )

  checkEdit(
    "directly-in-pkg3-old"
      .tag(
        IgnoreScalaVersion.for3LessThan("3.2.2")
      ),
    """|package examples {
       |  implicit class A (num: Int) { def incr: Int = num + 1 }
       |}
       |
       |package examples2 {
       |  object O {
       |    def main = 100.inc@@
       |  }
       |}
       |""".stripMargin,
    """|import examples.A
       |package examples {
       |  implicit class A (num: Int) { def incr: Int = num + 1 }
       |}
       |
       |package examples2 {
       |  object O {
       |    def main = 100.incr
       |  }
       |}
       |""".stripMargin,
    compat = Map(
      "2" -> """|package examples {
                |  implicit class A (num: Int) { def incr: Int = num + 1 }
                |}
                |
                |package examples2 {
                |
                |  import examples.A
                |  object O {
                |    def main = 100.incr
                |  }
                |}
                |""".stripMargin
    ),
    filter = _.contains("(implicit)")
  )

  check(
    "nested-pkg".tag(IgnoreScalaVersion.forLessThan("3.2.2")),
    """|package a:  // some comment
       |  package c:
       |    extension (num: Int)
       |        def increment2 = num + 2
       |  extension (num: Int)
       |    def increment = num + 1
       |
       |
       |package b:
       |  def main: Unit = 123.incre@@
       |""".stripMargin,
    """|increment: Int (extension)
       |increment2: Int (extension)
       |""".stripMargin
  )

  check(
    "nested-pkg-old"
      .tag(
        IgnoreScalaVersion.for3LessThan("3.2.2")
      ),
    """|package aa {  // some comment
       |  package cc {
       |    implicit class A (num: Int){
       |        def increment2 = num + 2
       |    }
       |  }
       |  implicit class A (num: Int) {
       |    def increment = num + 1
       |  }
       |}
       |
       |
       |package bb {
       |  object O {
       |    def main: Unit = 123.incre@@
       |  }
       |}
       |""".stripMargin,
    """|increment: Int (implicit)
       |increment2: Int (implicit)
       |""".stripMargin
  )

  check(
    "implicit-val-var",
    """|package example
       |
       |object Test {
       |  implicit class TestOps(val testArg: Int) {
       |    var testVar: Int = 42
       |    val testVal: Int = 42
       |    def testOps(b: Int): String = ???
       |  }
       |}
       |
       |object O {
       |  def main = 100.test@@
       |}
       |""".stripMargin,
    """|testArg: Int (implicit)
       |testVal: Int (implicit)
       |testVar: Int (implicit)
       |testOps(b: Int): String (implicit)
       |""".stripMargin,
    compat = Map(
      "2" ->
        """|testArg: Int (implicit)
           |testOps(b: Int): String (implicit)
           |testVal: Int (implicit)
           |testVar: Int (implicit)
           |""".stripMargin
    ),
    filter = _.contains("(implicit)")
  )

  checkEdit(
    "implicit-val-edit",
    """|package example
       |
       |object Test {
       |  implicit class TestOps(a: Int) {
       |    val testVal: Int = 42
       |  }
       |}
       |
       |object O {
       |  def main = 100.test@@
       |}
       |""".stripMargin,
    """|package example
       |
       |import example.Test.TestOps
       |
       |object Test {
       |  implicit class TestOps(a: Int) {
       |    val testVal: Int = 42
       |  }
       |}
       |
       |object O {
       |  def main = 100.testVal
       |}
       |""".stripMargin,
    filter = _.contains("(implicit)")
  )

  check(
    "complex-type-old",
    """|package example
       |
       |object Test {
       |  implicit class TestOps(a: List[Int]) {
       |    def testOps(b: Int) = ???
       |  }
       |}
       |
       |object ActualTest {
       |  List(1).tes@@
       |}
       |""".stripMargin,
    "testOps(b: Int): Nothing (implicit)",
    filter = _.contains("(implicit)")
  )

  check(
    "complex-type-old2".tag(IgnoreScala3),
    """|package example
       |
       |object Test {
       |  implicit class TestOps[T](a: List[T]) {
       |    def testOps(b: Int) = ???
       |  }
       |}
       |
       |object ActualTest {
       |  List(1).tes@@
       |}
       |""".stripMargin,
    "testOps(b: Int): Nothing (implicit)",
    filter = _.contains("(implicit)")
  )

  check(
    "complex-type-old3".tag(IgnoreScala3),
    """|package example
       |
       |case class A[-T](t: T)
       |
       |object Test {
       |  implicit class TestOps[T](a: A[T]) {
       |    def testOps(b: Int) = ???
       |  }
       |}
       |
       |object ActualTest {
       |  A(1).tes@@
       |}
       |""".stripMargin,
    "testOps(b: Int): Nothing (implicit)",
    filter = _.contains("(implicit)")
  )

}
