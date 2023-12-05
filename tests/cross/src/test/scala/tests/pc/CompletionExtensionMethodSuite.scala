package tests.pc

import tests.BaseCompletionSuite

class CompletionExtensionMethodSuite extends BaseCompletionSuite {

  override def ignoreScalaVersion: Option[IgnoreScalaVersion] =
    Some(IgnoreScala2)

  check(
    "simple",
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
    "simple2",
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
    "simple-empty",
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
    "filter-by-type",
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
    "filter-by-type-subtype",
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

  checkEdit(
    "simple-edit",
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
    "simple-edit-suffix",
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
    "name-conflict",
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
       |""".stripMargin
  )

  // NOTE: In 3.1.3, package object name includes the whole path to file
  // eg. in 3.2.2 we get `A$package`, but in 3.1.3 `/some/path/to/file/A$package`
  check(
    "directly-in-pkg1".tag(IgnoreScalaVersion.forLessThan("3.2.2")),
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
    "directly-in-pkg2".tag(IgnoreScalaVersion.forLessThan("3.2.2")),
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

  checkEdit(
    "directly-in-pkg3".tag(IgnoreScalaVersion.forLessThan("3.2.2")),
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

}
