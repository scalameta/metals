package tests.pc

import tests.BaseCompletionSuite

object CompletionMatchSuite extends BaseCompletionSuite {
  check(
    "match",
    """
      |object A {
      |  Option(1) match@@
      |}""".stripMargin,
    """|match
       |match (exhaustive) Option[Int] (2 cases)
       |""".stripMargin
  )

  check(
    "trailing-expression",
    """
      |object A {
      |  Option(1) match@@
      |  println(1)
      |}""".stripMargin,
    """|match
       |match (exhaustive) Option[Int] (2 cases)
       |""".stripMargin
  )

  check(
    "dot",
    """
      |object A {
      |  Option(1).match@@
      |}""".stripMargin,
    ""
  )

  check(
    "stale1",
    """package stale
      |sealed abstract class Weekday
      |case object Workday extends Weekday
      |case object Weekend extends Weekday
      |@@
      |""".stripMargin,
    "",
    filter = _ => false
  )
  if (!isScala211)
    // Assert that Workday/Weekend symbols from previous test don't appear in result.
    checkEdit(
      "stale2",
      """package stale
        |sealed abstract class Weekday
        |object Weekday {
        |  case object Workday extends Weekday
        |  case object Weekend extends Weekday
        |}
        |object App {
        |  null.asInstanceOf[Weekday] matc@@
        |}
        |""".stripMargin,
      // Tab characters are used to indicate user-configured indentation in the editor.
      // For example, in VS Code, the tab characters become 2 space indent by default.
      """|package stale
         |import stale.Weekday.Workday
         |import stale.Weekday.Weekend
         |sealed abstract class Weekday
         |object Weekday {
         |  case object Workday extends Weekday
         |  case object Weekend extends Weekday
         |}
         |object App {
         |  null.asInstanceOf[Weekday] match {
         |\tcase Workday => $0
         |\tcase Weekend =>
         |}
         |}
         |""".stripMargin,
      filter = _.contains("exhaustive")
    )
  checkEdit(
    "stale3",
    """package stale
      |sealed abstract class Weekday
      |case object Workday extends Weekday
      |case object Weekend extends Weekday
      |object App {
      |  null.asInstanceOf[Weekday] matc@@
      |}
      |""".stripMargin,
    """|package stale
       |sealed abstract class Weekday
       |case object Workday extends Weekday
       |case object Weekend extends Weekday
       |object App {
       |  null.asInstanceOf[Weekday] match {
       |\tcase Workday => $0
       |\tcase Weekend =>
       |}
       |}
       |""".stripMargin,
    filter = _.contains("exhaustive")
  )

  checkEdit(
    "Sort auto-completed exhaustive match keywords by declaration order",
    """package sort
      |sealed abstract class TestTree
      |case class Branch1(t1: TestTree) extends TestTree
      |case class Leaf(v: Int) extends TestTree
      |case class Branch2(t1: TestTree, t2: TestTree) extends TestTree
      |object App {
      |  null.asInstanceOf[TestTree] matc@@
      |}
      |""".stripMargin,
    """|package sort
       |sealed abstract class TestTree
       |case class Branch1(t1: TestTree) extends TestTree
       |case class Leaf(v: Int) extends TestTree
       |case class Branch2(t1: TestTree, t2: TestTree) extends TestTree
       |object App {
       |  null.asInstanceOf[TestTree] match {
       |\tcase Branch1(t1) => $0
       |\tcase Leaf(v) =>
       |\tcase Branch2(t1, t2) =>
       |}
       |}
       |""".stripMargin,
    filter = _.contains("exhaustive")
  )

  check(
    "inner-class",
    """
      |package example
      |
      |sealed abstract class Foo
      |object Foo {
      |  case object Bar extends Foo
      |  case class App(a: Int, b: String) extends Foo
      |}
      |object Main {
      |  val foo: Foo = ???
      |  foo match@@
      |}
      |""".stripMargin,
    """|match
       |match (exhaustive) Foo (2 cases)
       |""".stripMargin,
    compat = Map(
      "2.11" -> "match"
    )
  )
}
