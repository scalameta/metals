package tests.pc

import tests.BaseCompletionSuite

class CompletionMatchSuite extends BaseCompletionSuite {
  override def requiresScalaLibrarySources: Boolean = true

  override val compatProcess: Map[String, String => String] = Map(
    "2.11" -> { (s: String) => s.replace("Some(value)", "Some(x)") }
  )

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
                |match (exhaustive) Option[Int] (2 cases)
                |""".stripMargin
    )
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
    "stale2".tag(IgnoreScala211),
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
    )
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
    )
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
    )
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
    filter = _.contains("exhaustive")
  )

  check(
    "inner-class".tag(IgnoreScala211),
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
       |""".stripMargin
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
    )
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
    filter = _.contains("exhaustive")
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
    )
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
    )
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
    )
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
      "3" -> "case (exhaustive) Option[Int] (2 cases)"
    ),
    filter = _.contains("exhaustive")
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
    filter = _.contains("exhaustive")
  )

  checkEdit(
    "exhaustive-map-edit-2",
    """|sealed trait B
       |case class C(c: Int) extends B
       |case class D(d: Int) extends B
       |case class E(e: Int) extends B
       |
       |object A {
       |  val b: B = ???
       |  List(b).map{cas@@
       |  }
       |}""".stripMargin,
    s"""|sealed trait B
        |case class C(c: Int) extends B
        |case class D(d: Int) extends B
        |case class E(e: Int) extends B
        |
        |object A {
        |  val b: B = ???
        |  List(b).map{
        |\tcase C(c) => $$0
        |\tcase D(d) =>
        |\tcase E(e) =>
        |  }
        |}""".stripMargin,
    filter = _.contains("exhaustive")
  )

  checkEdit(
    "exhaustive-map-edit-3",
    s"""|sealed trait B
        |case class C(c: Int) extends B
        |case class D(d: Int) extends B
        |case class E(e: Int) extends B
        |
        |object A {
        |  val b: B = ???
        |  List(b).map{
        |\tcas@@
        |  }
        |}""".stripMargin,
    s"""|sealed trait B
        |case class C(c: Int) extends B
        |case class D(d: Int) extends B
        |case class E(e: Int) extends B
        |
        |object A {
        |  val b: B = ???
        |  List(b).map{
        |\tcase C(c) => $$0
        |case D(d) =>
        |case E(e) =>
        |  }
        |}""".stripMargin,
    filter = _.contains("exhaustive")
  )

  checkEdit(
    "exhaustive-enum-tags".tag(IgnoreScala2),
    s"""|object Tags:
        |  trait Hobby
        |  trait Chore
        |  trait Physical
        |
        |
        |import Tags.*
        |
        |enum Activity:
        |  case Reading(book: String, author: String) extends Activity, Hobby
        |  case Sports(time: Long, intensity: Double) extends Activity, Physical, Hobby
        |  case Cleaning                              extends Activity, Physical, Chore
        |  case Singing(song: String)                 extends Activity, Hobby
        |  case DishWashing(amount: Int)              extends Activity, Chore
        |
        |import Activity.*
        |
        |def energySpend(act: Activity & (Physical | Chore)): Double =
        |  act mat@@
        |
        |""".stripMargin,
    s"""|object Tags:
        |  trait Hobby
        |  trait Chore
        |  trait Physical
        |
        |
        |import Tags.*
        |
        |enum Activity:
        |  case Reading(book: String, author: String) extends Activity, Hobby
        |  case Sports(time: Long, intensity: Double) extends Activity, Physical, Hobby
        |  case Cleaning                              extends Activity, Physical, Chore
        |  case Singing(song: String)                 extends Activity, Hobby
        |  case DishWashing(amount: Int)              extends Activity, Chore
        |
        |import Activity.*
        |
        |def energySpend(act: Activity & (Physical | Chore)): Double =
        |  act match
        |\tcase Sports(time, intensity) => $$0
        |\tcase Cleaning =>
        |\tcase DishWashing(amount) =>
        |
        |""".stripMargin,
    filter = _.contains("exhaustive")
  )

  checkEdit(
    "exhaustive-enum-tags2".tag(IgnoreScala2),
    s"""|object Tags:
        |  trait Hobby
        |  trait Chore
        |  trait Physical
        |
        |
        |import Tags.*
        |
        |enum Activity:
        |  case Reading(book: String, author: String) extends Activity, Hobby
        |  case Sports(time: Long, intensity: Double) extends Activity, Physical, Hobby
        |  case Cleaning                              extends Activity, Physical, Chore
        |  case Singing(song: String)                 extends Activity, Hobby
        |  case DishWashing(amount: Int)              extends Activity, Chore
        |
        |import Activity.*
        |
        |def energySpend(act: Activity & Physical): Double =
        |  act mat@@
        |
        |""".stripMargin,
    s"""|object Tags:
        |  trait Hobby
        |  trait Chore
        |  trait Physical
        |
        |
        |import Tags.*
        |
        |enum Activity:
        |  case Reading(book: String, author: String) extends Activity, Hobby
        |  case Sports(time: Long, intensity: Double) extends Activity, Physical, Hobby
        |  case Cleaning                              extends Activity, Physical, Chore
        |  case Singing(song: String)                 extends Activity, Hobby
        |  case DishWashing(amount: Int)              extends Activity, Chore
        |
        |import Activity.*
        |
        |def energySpend(act: Activity & Physical): Double =
        |  act match
        |\tcase Sports(time, intensity) => $$0
        |\tcase Cleaning =>
        |
        |""".stripMargin,
    filter = _.contains("exhaustive")
  )

  checkEdit(
    "exhaustive-enum-tags3".tag(IgnoreScala2),
    s"""|object Tags:
        |  sealed trait Hobby
        |  sealed trait Chore
        |  sealed trait Physical
        |
        |
        |import Tags.*
        |
        |enum Activity:
        |  case Reading(book: String, author: String) extends Activity, Hobby
        |  case Sports(time: Long, intensity: Double) extends Activity, Physical, Hobby
        |  case Cleaning(time: Int)                   extends Activity, Physical, Chore
        |  case Singing(song: String)                 extends Activity, Hobby
        |  case DishWashing(amount: Int)              extends Activity, Chore
        |
        |import Activity.*
        |
        |def energySpend(act: Hobby | Physical & Chore): Double =
        |  act mat@@
        |
        |""".stripMargin,
    s"""|object Tags:
        |  sealed trait Hobby
        |  sealed trait Chore
        |  sealed trait Physical
        |
        |
        |import Tags.*
        |
        |enum Activity:
        |  case Reading(book: String, author: String) extends Activity, Hobby
        |  case Sports(time: Long, intensity: Double) extends Activity, Physical, Hobby
        |  case Cleaning(time: Int)                   extends Activity, Physical, Chore
        |  case Singing(song: String)                 extends Activity, Hobby
        |  case DishWashing(amount: Int)              extends Activity, Chore
        |
        |import Activity.*
        |
        |def energySpend(act: Hobby | Physical & Chore): Double =
        |  act match
        |\tcase Reading(book, author) => $$0
        |\tcase Sports(time, intensity) =>
        |\tcase Cleaning(time) =>
        |\tcase Singing(song) =>
        |
        |""".stripMargin,
    filter = _.contains("exhaustive")
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
    filter = _.contains("exhaustive")
  )

  check(
    "stale-symbols".tag(IgnoreScala211),
    """
      |package example
      |
      |object Main {
      |  val x: ScalaTargetType = ???
      |  val y = x match@@
      |}
      |sealed trait ScalaTargetType
      |object ScalaTargetType {
      |  case object Scala2 extends ScalaTargetType
      |  case object Scala3 extends ScalaTargetType
      |  case object JS extends ScalaTargetType
      |  case object Native extends ScalaTargetType
      |  case object Typelevel extends ScalaTargetType
      |  case object ScalaCli extends ScalaTargetType
      |}""".stripMargin,
    """|match
       |match (exhaustive) ScalaTargetType (6 cases)
       |""".stripMargin
  )

  checkEdit(
    "type-alias".tag(IgnoreScala2),
    s"""|object O {
        | type Id[A] = A
        |
        | enum Animal:
        |   case Cat, Dog
        |
        | val animal: Id[Animal] = ???
        |
        | animal ma@@
        |}
        |""".stripMargin,
    s"""object O {
       | type Id[A] = A
       |
       | enum Animal:
       |   case Cat, Dog
       |
       | val animal: Id[Animal] = ???
       |
       | animal match
       |\tcase Animal.Cat => $$0
       |\tcase Animal.Dog =>
       |
       |}
       |""".stripMargin,
    filter = _.contains("exhaustive")
  )

  checkEdit(
    "type-alias-sealed-trait".tag(IgnoreScala211),
    s"""|object O {
        | type Id[A] = A
        |
        |sealed trait Animal
        |object Animal {
        |   case object Cat extends Animal
        |   case object Dog extends Animal
        |}
        |
        | val animal: Id[Animal] = ???
        |
        |animal ma@@
        |}
        |""".stripMargin,
    s"""
       |import O.Animal.Cat
       |import O.Animal.Dog
       |object O {
       | type Id[A] = A
       |
       |sealed trait Animal
       |object Animal {
       |   case object Cat extends Animal
       |   case object Dog extends Animal
       |}
       |
       | val animal: Id[Animal] = ???
       |
       |animal match {
       |\tcase Cat => $$0
       |\tcase Dog =>
       |}
       |}
       |""".stripMargin,
    compat = Map(
      "3" ->
        s"""
           |import O.Animal.Cat
           |import O.Animal.Dog
           |object O {
           | type Id[A] = A
           |
           |sealed trait Animal
           |object Animal {
           |   case object Cat extends Animal
           |   case object Dog extends Animal
           |}
           |
           | val animal: Id[Animal] = ???
           |
           |animal match
           |\tcase Cat => $$0
           |\tcase Dog =>
           |
           |}
           |""".stripMargin
    ),
    filter = _.contains("exhaustive")
  )

  check(
    "union-type".tag(IgnoreScala2),
    """
      |case class Foo(a: Int)
      |case class Bar(b: Int)
      |
      |object O {
      |  val x: Foo | Bar = ???
      |  val y  = x match@@
      |}""".stripMargin,
    """|match
       |match (exhaustive) Foo | Bar (2 cases)
       |""".stripMargin
  )

  checkEdit(
    "union-type-edit".tag(IgnoreScala2),
    """
      |case class Foo(a: Int)
      |case class Bar(b: Int)
      |
      |object O {
      |  val x: Foo | Bar = ???
      |  val y  = x match@@
      |}""".stripMargin,
    s"""|case class Foo(a: Int)
        |case class Bar(b: Int)
        |
        |object O {
        |  val x: Foo | Bar = ???
        |  val y  = x match
        |\tcase Foo(a) => $$0
        |\tcase Bar(b) =>
        |
        |}
        |""".stripMargin,
    filter = _.contains("exhaustive")
  )

}
