package tests.pc

import tests.BaseCompletionSuite
import tests.BuildInfoVersions

class CompletionMatchSuite extends BaseCompletionSuite {

  override def requiresScalaLibrarySources: Boolean = true

  override def excludedScalaVersions: Set[String] =
    BuildInfoVersions.scala3Versions.toSet

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

  // Assert that Workday/Weekend symbols from previous test don't appear in result.
  checkEdit(
    "stale2".tag(IgnoreScalaVersion("2.11.12")),
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
    "exhaustive-sorting",
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

  checkEdit(
    "exhaustive-sorting-scalalib",
    """package sort
      |object App {
      |  Option(1) matc@@
      |}
      |""".stripMargin,
    """package sort
      |object App {
      |  Option(1) match {
      |\tcase Some(value) => $0
      |\tcase None =>
      |}
      |}
      |""".stripMargin,
    compat = Map(
      "2.11.12" ->
        """package sort
          |object App {
          |  Option(1) match {
          |\tcase Some(x) => $0
          |\tcase None =>
          |}
          |}
          |""".stripMargin
    ),
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

  checkEdit(
    "exhaustive-java-enum",
    """
      |package example
      |
      |import java.nio.file.AccessMode
      |
      |object Main {
      |  (null: AccessMode) match@@
      |}""".stripMargin,
    """
      |package example
      |
      |import java.nio.file.AccessMode
      |import java.nio.file.AccessMode.READ
      |import java.nio.file.AccessMode.WRITE
      |import java.nio.file.AccessMode.EXECUTE
      |
      |object Main {
      |  (null: AccessMode) match {
      |\tcase READ => $0
      |\tcase WRITE =>
      |\tcase EXECUTE =>
      |}
      |}""".stripMargin,
    filter = _.contains("exhaustive")
  )

  // https://github.com/scalameta/metals/issues/1253
  checkEdit(
    "exhaustive-fully-qualify".fail,
    """
      |package example
      |
      |object None
      |
      |object Main {
      |  Option(1) match@@
      |}""".stripMargin,
    """
      |package example
      |
      |object None
      |
      |object Main {
      |  Option(1) match {
      |\tcase Some(value) => $0
      |\tcase scala.None =>
      |}
      |}""".stripMargin,
    filter = _.contains("exhaustive")
  )

  checkEdit(
    "exhaustive-upper-type-bounds",
    """
      |package example
      |
      |sealed trait Test
      |case object Foo extends Test
      |case object Bar extends Test
      |
      |object Main {
      |  def testExhaustive[T <: Test](test: T): Boolean =
      |    test m@@
      |}""".stripMargin,
    """
      |package example
      |
      |sealed trait Test
      |case object Foo extends Test
      |case object Bar extends Test
      |
      |object Main {
      |  def testExhaustive[T <: Test](test: T): Boolean =
      |    test match {
      |\tcase Foo => $0
      |\tcase Bar =>
      |}
      |}""".stripMargin,
    filter = _.contains("exhaustive")
  )

  checkEdit(
    "exhaustive-upper-type-bounds with refinement",
    """
      |package example
      |
      |sealed trait TestA
      |case object Foo extends TestA
      |case object Bar extends TestA
      |sealed trait TestB
      |case object Baz extends TestB
      |case object Goo extends TestB
      |
      |object Main {
      |  def testExhaustive[T <: TestA with TestB](test: T): Boolean =
      |    test m@@
      |}""".stripMargin,
    """
      |package example
      |
      |sealed trait TestA
      |case object Foo extends TestA
      |case object Bar extends TestA
      |sealed trait TestB
      |case object Baz extends TestB
      |case object Goo extends TestB
      |
      |object Main {
      |  def testExhaustive[T <: TestA with TestB](test: T): Boolean =
      |    test match {
      |\tcase Foo => $0
      |\tcase Bar =>
      |\tcase Baz =>
      |\tcase Goo =>
      |}
      |}""".stripMargin,
    filter = _.contains("exhaustive")
  )
}
