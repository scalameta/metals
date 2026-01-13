package tests.mbt

class MbtScalaImplementationSuite
    extends BaseMbtReferenceSuite("mbt-scala-implementation") {

  testLSP("basic") {
    cleanWorkspace()
    val a = "a/src/main/scala/a/Animal.scala"
    val b = "b/src/main/scala/b/Bat.scala"
    val c = "c/src/main/scala/c/Ferrari.scala"
    val d = "d/src/main/scala/d/FruitBat.scala"
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {},
           |  "b": {"dependsOn": ["a"]}
           |}
           |/$a
           |package a
           |abstract class Animal {
           |  def makeSound(): String
           |  class Dog extends Animal {
           |    override def makeSound(): String = "Woof"
           |  }
           |}
           |/$b
           |package b
           |class Bat extends a.Animal {
           |  override def makeSound(): String = "Squeak"
           |  def fly(): Unit = println("Flying")
           |}
           |/$c
           |package c
           |class Ferrari {
           |  def makeSound(): String = "Vroom"
           |}
           |/$d
           |package d
           |import b.Bat
           |class FruitBat extends Bat {
           |  override def makeSound(): String = "Squeak"
           |  sealed trait Kind
           |  object Kind {
           |    case object Fruit extends Kind
           |    case object NoFruit extends Kind
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpenAndFocus(a)
      _ <- server.assertImplementationsSubquery(
        a,
        "def make@@Sound()",
        """|a/src/main/scala/a/Animal.scala:5:18: implementation
           |    override def makeSound(): String = "Woof"
           |                 ^^^^^^^^^
           |b/src/main/scala/b/Bat.scala:3:16: implementation
           |  override def makeSound(): String = "Squeak"
           |               ^^^^^^^^^
           |d/src/main/scala/d/FruitBat.scala:4:16: implementation
           |  override def makeSound(): String = "Squeak"
           |               ^^^^^^^^^
           |""".stripMargin,
      )
      _ <- server.assertImplementationsSubquery(
        a,
        "class Anim@@al",
        """|a/src/main/scala/a/Animal.scala:4:9: implementation
           |  class Dog extends Animal {
           |        ^^^
           |b/src/main/scala/b/Bat.scala:2:7: implementation
           |class Bat extends a.Animal {
           |      ^^^
           |d/src/main/scala/d/FruitBat.scala:3:7: implementation
           |class FruitBat extends Bat {
           |      ^^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  testLSP("type-parameters") {
    cleanWorkspace()
    val a = "a/src/main/scala/a/Foo.scala"
    val b = "a/src/main/scala/a/Bar.scala"
    val c = "a/src/main/scala/a/Baz.scala"
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {}
           |}
           |/$a
           |package a
           |abstract class Foo[T] {
           |  def bar(elem: T): T
           |}
           |/$b
           |package a
           |class Bar extends Foo[Int] {
           |  override def bar(elem: Int): Int = elem
           |}
           |/$c
           |package a
           |class Baz extends Foo[String] {
           |  override def bar(elem: String): String = elem
           |}
           |""".stripMargin
      )
      _ <- server.didOpenAndFocus(a)
      _ <- server.assertImplementationsSubquery(
        a,
        "def b@@ar(elem: T)",
        """|a/src/main/scala/a/Bar.scala:3:16: implementation
           |  override def bar(elem: Int): Int = elem
           |               ^^^
           |a/src/main/scala/a/Baz.scala:3:16: implementation
           |  override def bar(elem: String): String = elem
           |               ^^^
           |""".stripMargin,
      )
      _ <- server.assertImplementationsSubquery(
        a,
        "class F@@oo[T]",
        """|a/src/main/scala/a/Bar.scala:2:7: implementation
           |class Bar extends Foo[Int] {
           |      ^^^
           |a/src/main/scala/a/Baz.scala:2:7: implementation
           |class Baz extends Foo[String] {
           |      ^^^
           |""".stripMargin,
      )
    } yield ()
  }

  testLSP("trait-multiple-inheritance") {
    cleanWorkspace()
    val a = "a/src/main/scala/a/Top.scala"
    val b = "a/src/main/scala/a/Left.scala"
    val c = "a/src/main/scala/a/Right.scala"
    val d = "a/src/main/scala/a/Bottom.scala"
    val e = "a/src/main/scala/a/Direct.scala"
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {}
           |}
           |/$a
           |package a
           |trait Top {
           |  def method(): Unit
           |}
           |/$b
           |package a
           |trait Left extends Top
           |/$c
           |package a
           |trait Right extends Top
           |/$d
           |package a
           |class Bottom extends Left with Right {
           |  override def method(): Unit = ()
           |}
           |/$e
           |package a
           |class Direct extends Top {
           |  override def method(): Unit = ()
           |}
           |""".stripMargin
      )
      _ <- server.didOpenAndFocus(a)
      _ <- server.assertImplementationsSubquery(
        a,
        "def meth@@od()",
        """|a/src/main/scala/a/Bottom.scala:3:16: implementation
           |  override def method(): Unit = ()
           |               ^^^^^^
           |a/src/main/scala/a/Direct.scala:3:16: implementation
           |  override def method(): Unit = ()
           |               ^^^^^^
           |""".stripMargin,
      )
      _ <- server.assertImplementationsSubquery(
        a,
        "trait T@@op",
        """|a/src/main/scala/a/Bottom.scala:2:7: implementation
           |class Bottom extends Left with Right {
           |      ^^^^^^
           |a/src/main/scala/a/Direct.scala:2:7: implementation
           |class Direct extends Top {
           |      ^^^^^^
           |a/src/main/scala/a/Left.scala:2:7: implementation
           |trait Left extends Top
           |      ^^^^
           |a/src/main/scala/a/Right.scala:2:7: implementation
           |trait Right extends Top
           |      ^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  testLSP("sealed-trait-implementations") {
    cleanWorkspace()
    val a = "a/src/main/scala/a/Expr.scala"
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {}
           |}
           |/$a
           |package a
           |sealed trait Expr {
           |  def eval: Int
           |}
           |case class Lit(value: Int) extends Expr {
           |  def eval: Int = value
           |}
           |case class Add(left: Expr, right: Expr) extends Expr {
           |  def eval: Int = left.eval + right.eval
           |}
           |case class Mul(left: Expr, right: Expr) extends Expr {
           |  def eval: Int = left.eval * right.eval
           |}
           |""".stripMargin
      )
      _ <- server.didOpenAndFocus(a)
      _ <- server.assertImplementationsSubquery(
        a,
        "sealed trait Ex@@pr",
        """|a/src/main/scala/a/Expr.scala:5:12: implementation
           |case class Lit(value: Int) extends Expr {
           |           ^^^
           |a/src/main/scala/a/Expr.scala:8:12: implementation
           |case class Add(left: Expr, right: Expr) extends Expr {
           |           ^^^
           |a/src/main/scala/a/Expr.scala:11:12: implementation
           |case class Mul(left: Expr, right: Expr) extends Expr {
           |           ^^^
           |""".stripMargin,
      )
      _ <- server.assertImplementationsSubquery(
        a,
        "def ev@@al: Int",
        """|a/src/main/scala/a/Expr.scala:6:7: implementation
           |  def eval: Int = value
           |      ^^^^
           |a/src/main/scala/a/Expr.scala:9:7: implementation
           |  def eval: Int = left.eval + right.eval
           |      ^^^^
           |a/src/main/scala/a/Expr.scala:12:7: implementation
           |  def eval: Int = left.eval * right.eval
           |      ^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  testLSP("case-object-implementations") {
    cleanWorkspace()
    val a = "a/src/main/scala/a/Color.scala"
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {}
           |}
           |/$a
           |package a
           |sealed trait Color {
           |  def rgb: (Int, Int, Int)
           |}
           |case object Red extends Color {
           |  def rgb: (Int, Int, Int) = (255, 0, 0)
           |}
           |case object Green extends Color {
           |  def rgb: (Int, Int, Int) = (0, 255, 0)
           |}
           |case object Blue extends Color {
           |  def rgb: (Int, Int, Int) = (0, 0, 255)
           |}
           |""".stripMargin
      )
      _ <- server.didOpenAndFocus(a)
      _ <- server.assertImplementationsSubquery(
        a,
        "sealed trait Col@@or",
        """|a/src/main/scala/a/Color.scala:5:13: implementation
           |case object Red extends Color {
           |            ^^^
           |a/src/main/scala/a/Color.scala:8:13: implementation
           |case object Green extends Color {
           |            ^^^^^
           |a/src/main/scala/a/Color.scala:11:13: implementation
           |case object Blue extends Color {
           |            ^^^^
           |""".stripMargin,
      )
      _ <- server.assertImplementationsSubquery(
        a,
        "def rg@@b: (Int, Int, Int)",
        """|a/src/main/scala/a/Color.scala:6:7: implementation
           |  def rgb: (Int, Int, Int) = (255, 0, 0)
           |      ^^^
           |a/src/main/scala/a/Color.scala:9:7: implementation
           |  def rgb: (Int, Int, Int) = (0, 255, 0)
           |      ^^^
           |a/src/main/scala/a/Color.scala:12:7: implementation
           |  def rgb: (Int, Int, Int) = (0, 0, 255)
           |      ^^^
           |""".stripMargin,
      )
    } yield ()
  }

  testLSP("abstract-class-with-val") {
    cleanWorkspace()
    val a = "a/src/main/scala/a/Shape.scala"
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {}
           |}
           |/$a
           |package a
           |abstract class Shape {
           |  def area: Double
           |  val name: String
           |}
           |class Circle(radius: Double) extends Shape {
           |  def area: Double = math.Pi * radius * radius
           |  val name: String = "Circle"
           |}
           |class Rectangle(width: Double, height: Double) extends Shape {
           |  def area: Double = width * height
           |  val name: String = "Rectangle"
           |}
           |""".stripMargin
      )
      _ <- server.didOpenAndFocus(a)
      _ <- server.assertImplementationsSubquery(
        a,
        "abstract class Sha@@pe",
        """|a/src/main/scala/a/Shape.scala:6:7: implementation
           |class Circle(radius: Double) extends Shape {
           |      ^^^^^^
           |a/src/main/scala/a/Shape.scala:10:7: implementation
           |class Rectangle(width: Double, height: Double) extends Shape {
           |      ^^^^^^^^^
           |""".stripMargin,
      )
      _ <- server.assertImplementationsSubquery(
        a,
        "def ar@@ea: Double",
        """|a/src/main/scala/a/Shape.scala:7:7: implementation
           |  def area: Double = math.Pi * radius * radius
           |      ^^^^
           |a/src/main/scala/a/Shape.scala:11:7: implementation
           |  def area: Double = width * height
           |      ^^^^
           |""".stripMargin,
      )
      _ <- server.assertImplementationsSubquery(
        a,
        "val na@@me: String",
        """|a/src/main/scala/a/Shape.scala:8:7: implementation
           |  val name: String = "Circle"
           |      ^^^^
           |a/src/main/scala/a/Shape.scala:12:7: implementation
           |  val name: String = "Rectangle"
           |      ^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  testLSP("abstract-class-with-var") {
    cleanWorkspace()
    val a = "a/src/main/scala/a/Shape.scala"
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {}
           |}
           |/$a
           |package a
           |abstract class Shape {
           |  var name: String
           |}
           |class Circle(radius: Double) extends Shape {
           |  def name: String = "Circle"
           |  def name_=(value: String): Unit = () // should match the setter, but not yet working
           |}
           |class Rectangle(width: Double, height: Double) extends Shape {
           |  var name: String = "Rectangle"
           |}
           |""".stripMargin
      )
      _ <- server.didOpenAndFocus(a)
      _ <- server.assertImplementationsSubquery(
        a,
        "var na@@me: String",
        """|a/src/main/scala/a/Shape.scala:6:7: implementation
           |  def name: String = "Circle"
           |      ^^^^
           |a/src/main/scala/a/Shape.scala:10:7: implementation
           |  var name: String = "Rectangle"
           |      ^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  testLSP("cross-project-implementations") {
    cleanWorkspace()
    val a = "a/src/main/scala/a/Service.scala"
    val b = "b/src/main/scala/b/DbService.scala"
    val c = "c/src/main/scala/c/CacheService.scala"
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {},
           |  "b": {"dependsOn": ["a"]},
           |  "c": {"dependsOn": ["a"]}
           |}
           |/$a
           |package a
           |trait Service {
           |  def start(): Unit
           |  def stop(): Unit
           |}
           |/$b
           |package b
           |import a.Service
           |class DbService extends Service {
           |  override def start(): Unit = println("DB started")
           |  override def stop(): Unit = println("DB stopped")
           |}
           |/$c
           |package c
           |import a.Service
           |class CacheService extends Service {
           |  override def start(): Unit = println("Cache started")
           |  override def stop(): Unit = println("Cache stopped")
           |}
           |""".stripMargin
      )
      _ <- server.didOpenAndFocus(a)
      _ <- server.assertImplementationsSubquery(
        a,
        "trait Serv@@ice",
        """|b/src/main/scala/b/DbService.scala:3:7: implementation
           |class DbService extends Service {
           |      ^^^^^^^^^
           |c/src/main/scala/c/CacheService.scala:3:7: implementation
           |class CacheService extends Service {
           |      ^^^^^^^^^^^^
           |""".stripMargin,
      )
      _ <- server.assertImplementationsSubquery(
        a,
        "def sta@@rt()",
        """|b/src/main/scala/b/DbService.scala:4:16: implementation
           |  override def start(): Unit = println("DB started")
           |               ^^^^^
           |c/src/main/scala/c/CacheService.scala:4:16: implementation
           |  override def start(): Unit = println("Cache started")
           |               ^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  testLSP("nested-class-implementations") {
    cleanWorkspace()
    val a = "a/src/main/scala/a/Outer.scala"
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {}
           |}
           |/$a
           |package a
           |object Outer {
           |  trait Inner {
           |    def process(): String
           |  }
           |  class Impl1 extends Inner {
           |    def process(): String = "impl1"
           |  }
           |  class Impl2 extends Inner {
           |    def process(): String = "impl2"
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpenAndFocus(a)
      _ <- server.assertImplementationsSubquery(
        a,
        "trait Inn@@er",
        """|a/src/main/scala/a/Outer.scala:6:9: implementation
           |  class Impl1 extends Inner {
           |        ^^^^^
           |a/src/main/scala/a/Outer.scala:9:9: implementation
           |  class Impl2 extends Inner {
           |        ^^^^^
           |""".stripMargin,
      )
      _ <- server.assertImplementationsSubquery(
        a,
        "def proc@@ess()",
        """|a/src/main/scala/a/Outer.scala:7:9: implementation
           |    def process(): String = "impl1"
           |        ^^^^^^^
           |a/src/main/scala/a/Outer.scala:10:9: implementation
           |    def process(): String = "impl2"
           |        ^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  testLSP("self-type-implementations") {
    cleanWorkspace()
    val a = "a/src/main/scala/a/Component.scala"
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {}
           |}
           |/$a
           |package a
           |trait Logging {
           |  def log(msg: String): Unit
           |}
           |trait Database { self: Logging =>
           |  def query(sql: String): Unit = {
           |    log(s"Executing: $$sql")
           |  }
           |}
           |class MySqlDatabase extends Database with Logging {
           |  def log(msg: String): Unit = println(msg)
           |}
           |class PostgresDatabase extends Database with Logging {
           |  def log(msg: String): Unit = println(s"[PG] $$msg")
           |}
           |""".stripMargin
      )
      _ <- server.didOpenAndFocus(a)
      _ <- server.assertImplementationsSubquery(
        a,
        "trait Log@@ging",
        """|a/src/main/scala/a/Component.scala:10:7: implementation
           |class MySqlDatabase extends Database with Logging {
           |      ^^^^^^^^^^^^^
           |a/src/main/scala/a/Component.scala:13:7: implementation
           |class PostgresDatabase extends Database with Logging {
           |      ^^^^^^^^^^^^^^^^
           |""".stripMargin,
      )
      _ <- server.assertImplementationsSubquery(
        a,
        "def lo@@g(msg: String)",
        """|a/src/main/scala/a/Component.scala:11:7: implementation
           |  def log(msg: String): Unit = println(msg)
           |      ^^^
           |a/src/main/scala/a/Component.scala:14:7: implementation
           |  def log(msg: String): Unit = println(s"[PG] $msg")
           |      ^^^
           |""".stripMargin,
      )
    } yield ()
  }

  testLSP("def-implemented-as-val-or-var") {
    cleanWorkspace()
    val a = "a/src/main/scala/a/Named.scala"
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {}
           |}
           |/$a
           |package a
           |trait Named {
           |  def name: String
           |}
           |class Person extends Named {
           |  val name: String = "Alice"
           |}
           |class MutablePerson extends Named {
           |  var name: String = "Bob"
           |}
           |class MethodPerson extends Named {
           |  def name: String = "Charlie"
           |}
           |""".stripMargin
      )
      _ <- server.didOpenAndFocus(a)
      _ <- server.assertImplementationsSubquery(
        a,
        "trait Nam@@ed",
        """|a/src/main/scala/a/Named.scala:5:7: implementation
           |class Person extends Named {
           |      ^^^^^^
           |a/src/main/scala/a/Named.scala:8:7: implementation
           |class MutablePerson extends Named {
           |      ^^^^^^^^^^^^^
           |a/src/main/scala/a/Named.scala:11:7: implementation
           |class MethodPerson extends Named {
           |      ^^^^^^^^^^^^
           |""".stripMargin,
      )
      _ <- server.assertImplementationsSubquery(
        a,
        "def na@@me: String",
        """|a/src/main/scala/a/Named.scala:6:7: implementation
           |  val name: String = "Alice"
           |      ^^^^
           |a/src/main/scala/a/Named.scala:9:7: implementation
           |  var name: String = "Bob"
           |      ^^^^
           |a/src/main/scala/a/Named.scala:12:7: implementation
           |  def name: String = "Charlie"
           |      ^^^^
           |""".stripMargin,
      )
    } yield ()
  }
}
