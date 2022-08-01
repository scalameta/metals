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
       |""".stripMargin,
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
    filter = _.contains("(extension)"),
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
    filter = _.contains("(extension)"),
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
    filter = _.contains("(extension)"),
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
       |""".stripMargin,
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
       |""".stripMargin,
  )

}
