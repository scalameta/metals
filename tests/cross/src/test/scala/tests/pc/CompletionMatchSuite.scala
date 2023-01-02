package tests.pc

import tests.BaseCompletionSuite

class CompletionMatchSuite extends BaseCompletionSuite {
  override def requiresScalaLibrarySources: Boolean = true

  check(
    "match",
    """
      |object A {
      |  Option(1) match@@
      |}""".stripMargin,
    """|match
       |match (exhaustive) Option[Int] (2 cases)
       |""".stripMargin,
    compat = Map(
      "3" -> """|match
                |match (exhaustive) Option (2 cases)
                |""".stripMargin
    ),
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
       |""".stripMargin,
    compat = Map(
      "3" -> """|match
                |match (exhaustive) Option (2 cases)
                |""".stripMargin
    ),
  )

  // In Scala3 it's allowed to write xxx.match
  check(
    "dot",
    """
      |object A {
      |  Option(1).match@@
      |}""".stripMargin,
    "",
    compat = Map(
      "3" -> """|match
                |match (exhaustive) Option (2 cases)
                |""".stripMargin
    ),
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
    filter = _ => false,
  )

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
    s"""|package stale
        |import stale.Weekday.Workday
        |import stale.Weekday.Weekend
        |sealed abstract class Weekday
        |object Weekday {
        |  case object Workday extends Weekday
        |  case object Weekend extends Weekday
        |}
        |object App {
        |  null.asInstanceOf[Weekday] match {
        |\tcase Workday => $$0
        |\tcase Weekend =>
        |}
        |}
        |""".stripMargin,
    filter = _.contains("exhaustive"),
    compat = Map(
      "3" -> s"""|package stale
                 |
                 |import stale.Weekday.Workday
                 |import stale.Weekday.Weekend
                 |sealed abstract class Weekday
                 |object Weekday {
                 |  case object Workday extends Weekday
                 |  case object Weekend extends Weekday
                 |}
                 |object App {
                 |  null.asInstanceOf[Weekday] match
                 |\tcase Workday => $$0
                 |\tcase Weekend =>
                 |
                 |}
                 |""".stripMargin
    ),
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
    s"""|package stale
        |sealed abstract class Weekday
        |case object Workday extends Weekday
        |case object Weekend extends Weekday
        |object App {
        |  null.asInstanceOf[Weekday] match {
        |\tcase Workday => $$0
        |\tcase Weekend =>
        |}
        |}
        |""".stripMargin,
    filter = _.contains("exhaustive"),
    compat = Map(
      "3" -> s"""|package stale
                 |sealed abstract class Weekday
                 |case object Workday extends Weekday
                 |case object Weekend extends Weekday
                 |object App {
                 |  null.asInstanceOf[Weekday] match
                 |\tcase Workday => $$0
                 |\tcase Weekend =>
                 |
                 |}
                 |""".stripMargin
    ),
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
    s"""|package sort
        |sealed abstract class TestTree
        |case class Branch1(t1: TestTree) extends TestTree
        |case class Leaf(v: Int) extends TestTree
        |case class Branch2(t1: TestTree, t2: TestTree) extends TestTree
        |object App {
        |  null.asInstanceOf[TestTree] match {
        |\tcase Branch1(t1) => $$0
        |\tcase Leaf(v) =>
        |\tcase Branch2(t1, t2) =>
        |}
        |}
        |""".stripMargin,
    filter = _.contains("exhaustive"),
    compat = Map(
      "3" ->
        s"""|package sort
            |sealed abstract class TestTree
            |case class Branch1(t1: TestTree) extends TestTree
            |case class Leaf(v: Int) extends TestTree
            |case class Branch2(t1: TestTree, t2: TestTree) extends TestTree
            |object App {
            |  null.asInstanceOf[TestTree] match
            |\tcase Branch1(t1) => $$0
            |\tcase Leaf(v) =>
            |\tcase Branch2(t1, t2) =>
            |
            |}
            |""".stripMargin
    ),
  )

  checkEdit(
    "exhaustive-sorting-scalalib",
    """package sort
      |object App {
      |  Option(1) matc@@
      |}
      |""".stripMargin,
    s"""package sort
       |object App {
       |  Option(1) match {
       |\tcase Some(value) => $$0
       |\tcase None =>
       |}
       |}
       |""".stripMargin,
    compat = Map(
      "3" -> s"""package sort
                |object App {
                |  Option(1) match
                |\tcase Some(value) => $$0
                |\tcase None =>
                |
                |}
                |""".stripMargin
    ),
    filter = _.contains("exhaustive"),
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
  )

  checkEdit(
    "exhaustive-java-enum".tag(IgnoreScalaVersion.for3LessThan("3.2.0")),
    """
      |package example
      |
      |import java.nio.file.AccessMode
      |
      |object Main {
      |  (null: AccessMode) match@@
      |}""".stripMargin,
    s"""
       |package example
       |
       |import java.nio.file.AccessMode
       |import java.nio.file.AccessMode.READ
       |import java.nio.file.AccessMode.WRITE
       |import java.nio.file.AccessMode.EXECUTE
       |
       |object Main {
       |  (null: AccessMode) match {
       |\tcase READ => $$0
       |\tcase WRITE =>
       |\tcase EXECUTE =>
       |}
       |}""".stripMargin,
    filter = _.contains("exhaustive"),
    compat = Map(
      "3" -> s"""
                |package example
                |
                |import java.nio.file.AccessMode
                |
                |object Main {
                |  (null: AccessMode) match
                |\tcase AccessMode.READ => $$0
                |\tcase AccessMode.WRITE =>
                |\tcase AccessMode.EXECUTE =>
                |
                |}""".stripMargin
    ),
  )

  checkEdit(
    "exhaustive-scala-enum".tag(IgnoreScala2),
    """
      |package withenum {
      |enum Color(rank: Int):
      |  case Red extends Color(1)
      |  case Blue extends Color(2)
      |  case Green extends Color(3)
      |}
      |
      |package example
      |
      |object Main {
      |  val x: withenum.Color = ???
      |  x match@@
      |}""".stripMargin,
    s"""|import withenum.Color
        |
        |package withenum {
        |enum Color(rank: Int):
        |  case Red extends Color(1)
        |  case Blue extends Color(2)
        |  case Green extends Color(3)
        |}
        |
        |package example
        |
        |object Main {
        |  val x: withenum.Color = ???
        |  x match
        |\tcase Color.Red => $$0
        |\tcase Color.Blue =>
        |\tcase Color.Green =>
        |
        |}
        |""".stripMargin,
    filter = _.contains("exhaustive"),
  )

  checkEdit(
    "exhaustive-fully-qualify",
    """
      |package example
      |
      |object None
      |
      |object Main {
      |  Option(1) match@@
      |}""".stripMargin,
    s"""
       |package example
       |
       |object None
       |
       |object Main {
       |  Option(1) match {
       |\tcase Some(value) => $$0
       |\tcase scala.None =>
       |}
       |}""".stripMargin,
    filter = _.contains("exhaustive"),
    compat = Map(
      "3" -> s"""
                |package example
                |
                |object None
                |
                |object Main {
                |  Option(1) match
                |\tcase Some(value) => $$0
                |\tcase scala.None =>
                |
                |}""".stripMargin
    ),
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
    s"""
       |package example
       |
       |sealed trait Test
       |case object Foo extends Test
       |case object Bar extends Test
       |
       |object Main {
       |  def testExhaustive[T <: Test](test: T): Boolean =
       |    test match {
       |\tcase Foo => $$0
       |\tcase Bar =>
       |}
       |}""".stripMargin,
    filter = _.contains("exhaustive"),
    compat = Map(
      "3" -> s"""
                |package example
                |
                |sealed trait Test
                |case object Foo extends Test
                |case object Bar extends Test
                |
                |object Main {
                |  def testExhaustive[T <: Test](test: T): Boolean =
                |    test match
                |\tcase Foo => $$0
                |\tcase Bar =>
                |
                |}""".stripMargin
    ),
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
      |case object Both extends TestA with TestB
      |object Main {
      |  def testExhaustive[T <: TestA with TestB](test: T): Boolean =
      |    test m@@
      |}""".stripMargin,
    s"""
       |package example
       |
       |sealed trait TestA
       |case object Foo extends TestA
       |case object Bar extends TestA
       |sealed trait TestB
       |case object Baz extends TestB
       |case object Goo extends TestB
       |case object Both extends TestA with TestB
       |object Main {
       |  def testExhaustive[T <: TestA with TestB](test: T): Boolean =
       |    test match {
       |\tcase Both => $$0
       |\t
       |}
       |}""".stripMargin,
    filter = _.contains("exhaustive"),
    compat = Map(
      "3" -> s"""
                |package example
                |
                |sealed trait TestA
                |case object Foo extends TestA
                |case object Bar extends TestA
                |sealed trait TestB
                |case object Baz extends TestB
                |case object Goo extends TestB
                |case object Both extends TestA with TestB
                |object Main {
                |  def testExhaustive[T <: TestA with TestB](test: T): Boolean =
                |    test match
                |\tcase Both => $$0
                |\t
                |
                |}""".stripMargin
    ),
  )
  check(
    "exhaustive-map",
    """
      |object A {
      |  List(Option(1)).map{ ca@@ }
      |}""".stripMargin,
    """|case (exhaustive) Option[A] (2 cases)
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|case (exhaustive) Option (2 cases)
           |""".stripMargin
    ),
    filter = _.contains("exhaustive"),
  )

  checkEdit(
    "exhaustive-map-edit",
    """
      |object A {
      |  List(Option(1)).map{cas@@}
      |}""".stripMargin,
    s"""
       |object A {
       |  List(Option(1)).map{
       |\tcase Some(value) => $$0
       |\tcase None =>
       |}
       |}""".stripMargin,
    filter = _.contains("exhaustive"),
  )

  checkEdit(
    "exhaustive-rename".tag(IgnoreScala2),
    s"""|package b {
        |  enum Color: 
        |    case Red, Blue, Green
        |}
        |
        |package a {
        |object A {
        |  import b.Color as Clr
        |  val c: Clr = ???
        |  c ma@@
        |}
        |}""".stripMargin,
    s"""|package b {
        |  enum Color: 
        |    case Red, Blue, Green
        |}
        |
        |package a {
        |object A {
        |  import b.Color as Clr
        |  val c: Clr = ???
        |  c match
        |\tcase Clr.Red => $$0
        |\tcase Clr.Blue =>
        |\tcase Clr.Green =>
        |
        |}
        |}""".stripMargin,
    filter = _.contains("exhaustive"),
  )

}
