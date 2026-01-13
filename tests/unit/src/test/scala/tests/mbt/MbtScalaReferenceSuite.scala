package tests.mbt

class MbtScalaReferenceSuite
    extends BaseMbtReferenceSuite("mbt-scala-reference") {

  testLSP("field") {
    cleanWorkspace()
    val a = "a/src/main/scala/a/Upstream.scala"
    val b = "b/src/main/scala/b/B.scala"
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
           |object Upstream {
           |  val greeting: String = "Hello, World!"
           |  val greeting2: String = "Hello, World!"
           |}
           |/$b
           |package b
           |import a.Upstream._
           |object B {
           |  def main(args: Array[String]): Unit = {
           |    println(a.Upstream.greeting)
           |    println(greeting2)
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpenAndFocus(a)
      // with qualifier
      _ <- server.assertReferencesSubquery(
        a,
        "val greeti@@ng",
        """|a/src/main/scala/a/Upstream.scala:3:7: reference
           |  val greeting: String = "Hello, World!"
           |      ^^^^^^^^
           |b/src/main/scala/b/B.scala:5:24: reference
           |    println(a.Upstream.greeting)
           |                       ^^^^^^^^
           |""".stripMargin,
      )
      // without qualifier
      _ <- server.assertReferencesSubquery(
        a,
        "val greeti@@ng2",
        """|a/src/main/scala/a/Upstream.scala:4:7: reference
           |  val greeting2: String = "Hello, World!"
           |      ^^^^^^^^^
           |b/src/main/scala/b/B.scala:6:13: reference
           |    println(greeting2)
           |            ^^^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  testLSP("method") {
    cleanWorkspace()
    val a = "a/src/main/scala/a/Upstream.scala"
    val b = "b/src/main/scala/b/B.scala"
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
           |object Upstream {
           |  def hello(): String = "Hello, World!"
           |  def hello(i: Int): String = "Hello, World!"
           |  def hello(s: String): String = "Hello, World!"
           |  def hello2(): String = "Hello, World!"
           |}
           |/$b
           |package b
           |import a.Upstream.hello2
           |object B {
           |  def main(args: Array[String]): Unit = {
           |    println(a.Upstream.hello())
           |    println(hello2())
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpenAndFocus(a)
      // Asserts we find references that are qualified, and we don't include
      // other "hello()" overloads.
      _ <- server.assertReferencesSubquery(
        a,
        "def hel@@lo()",
        """|a/src/main/scala/a/Upstream.scala:3:7: reference
           |  def hello(): String = "Hello, World!"
           |      ^^^^^
           |b/src/main/scala/b/B.scala:5:24: reference
           |    println(a.Upstream.hello())
           |                       ^^^^^
           |""".stripMargin,
      )
      // Asserts we find references that are not qualified
      _ <- server.assertReferencesSubquery(
        a,
        "def hel@@lo2",
        """|a/src/main/scala/a/Upstream.scala:6:7: reference
           |  def hello2(): String = "Hello, World!"
           |      ^^^^^^
           |b/src/main/scala/b/B.scala:2:19: reference
           |import a.Upstream.hello2
           |                  ^^^^^^
           |b/src/main/scala/b/B.scala:6:13: reference
           |    println(hello2())
           |            ^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  testLSP("class") {
    cleanWorkspace()
    val a = "a/src/main/scala/a/Upstream.scala"
    val b = "a/src/main/scala/a/Downstream.scala"
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {}
           |}
           |/$a
           |package a
           |class Upstream(i: Int) {
           |  def this(i: Int, j: Int) = this(i + j) // this should be matched but it isn't yet
           |}
           |object Upstream {
           |  class Upstream2 {
           |    def magic(): Int = 42
           |  }
           |}
           |/$b
           |package a
           |object Downstream {
           |  def main(): Unit = {
           |    val a: Upstream = new Upstream(1)
           |    new Upstream(1, 2)
           |    new Upstream.Upstream2().magic()
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpenAndFocus(a)
      // usages as type, and constructor
      _ <- server.assertReferencesSubquery(
        a,
        "class Upstr@@eam",
        """|a/src/main/scala/a/Downstream.scala:4:12: reference
           |    val a: Upstream = new Upstream(1)
           |           ^^^^^^^^
           |a/src/main/scala/a/Downstream.scala:4:27: reference
           |    val a: Upstream = new Upstream(1)
           |                          ^^^^^^^^
           |a/src/main/scala/a/Downstream.scala:5:9: reference
           |    new Upstream(1, 2)
           |        ^^^^^^^^
           |a/src/main/scala/a/Downstream.scala:6:9: reference
           |    new Upstream.Upstream2().magic()
           |        ^^^^^^^^
           |a/src/main/scala/a/Upstream.scala:2:7: reference
           |class Upstream(i: Int) {
           |      ^^^^^^^^
           |""".stripMargin,
      )
      _ <- server.assertReferencesSubquery(
        a,
        "class Upstr@@eam2",
        """|a/src/main/scala/a/Downstream.scala:6:18: reference
           |    new Upstream.Upstream2().magic()
           |                 ^^^^^^^^^
           |a/src/main/scala/a/Upstream.scala:6:9: reference
           |  class Upstream2 {
           |        ^^^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  testLSP("case-class") {
    cleanWorkspace()
    val a = "a/src/main/scala/a/Point.scala"
    val b = "a/src/main/scala/a/Downstream.scala"
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {}
           |}
           |/$a
           |package a
           |case class Point(xx: Int, y: Int) {
           |  def sum: Int = xx + y
           |}
           |/$b
           |package a
           |object Downstream {
           |  def run(): Int = {
           |    val a: Point = Point(1, 2)
           |    a.xx + a.y + a.sum
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpenAndFocus(a)
      _ <- server.assertReferencesSubquery(
        a,
        "case class Po@@int",
        """|a/src/main/scala/a/Downstream.scala:4:12: reference
           |    val a: Point = Point(1, 2)
           |           ^^^^^
           |a/src/main/scala/a/Downstream.scala:4:20: reference
           |    val a: Point = Point(1, 2)
           |                   ^^^^^
           |a/src/main/scala/a/Point.scala:2:12: reference
           |case class Point(xx: Int, y: Int) {
           |           ^^^^^
           |""".stripMargin,
      )
      _ <- server.assertReferencesSubquery(
        a,
        "case class Point(x@@x",
        """|a/src/main/scala/a/Downstream.scala:5:7: reference
           |    a.xx + a.y + a.sum
           |      ^^
           |a/src/main/scala/a/Point.scala:2:18: reference
           |case class Point(xx: Int, y: Int) {
           |                 ^^
           |a/src/main/scala/a/Point.scala:3:18: reference
           |  def sum: Int = xx + y
           |                 ^^
           |""".stripMargin,
      )
    } yield ()
  }

  testLSP("pattern-match") {
    cleanWorkspace()
    val a = "a/src/main/scala/a/Point.scala"
    val b = "a/src/main/scala/a/Downstream.scala"
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {}
           |}
           |/$a
           |package a
           |case class Point(x: Int, y: Int)
           |/$b
           |package a
           |object Downstream {
           |  def patmat(x: Point) = x match {
           |    case Point(x, y) => x + y
           |  }
           |
           |  def patmat2(x: Option[Point]) = x match {
           |    case Some(Point(x, y)) => x + y
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpenAndFocus(a)
      _ <- server.assertReferencesSubquery(
        b,
        "case Po@@int(x, y) => x + y",
        """|a/src/main/scala/a/Downstream.scala:4:10: reference
           |    case Point(x, y) => x + y
           |         ^^^^^
           |a/src/main/scala/a/Downstream.scala:8:15: reference
           |    case Some(Point(x, y)) => x + y
           |              ^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  testLSP("sealed-trait") {
    cleanWorkspace()
    val a = "a/src/main/scala/a/Fruit.scala"
    val b = "a/src/main/scala/a/Downstream.scala"
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {}
           |}
           |/$a
           |package a
           |sealed trait Fruit
           |case object Apple extends Fruit
           |case object Banana extends Fruit
           |/$b
           |package a
           |object Downstream {
           |  def main(): Unit = {
           |    val f: Fruit = Apple
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpenAndFocus(a)
      _ <- server.assertReferencesSubquery(
        a,
        "sealed trait Fru@@it",
        """|a/src/main/scala/a/Downstream.scala:4:12: reference
           |    val f: Fruit = Apple
           |           ^^^^^
           |a/src/main/scala/a/Fruit.scala:2:14: reference
           |sealed trait Fruit
           |             ^^^^^
           |a/src/main/scala/a/Fruit.scala:3:27: reference
           |case object Apple extends Fruit
           |                          ^^^^^
           |a/src/main/scala/a/Fruit.scala:4:28: reference
           |case object Banana extends Fruit
           |                           ^^^^^
           |""".stripMargin,
      )
      _ <- server.assertReferencesSubquery(
        a,
        "case object Ap@@ple",
        """|a/src/main/scala/a/Downstream.scala:4:20: reference
           |    val f: Fruit = Apple
           |                   ^^^^^
           |a/src/main/scala/a/Fruit.scala:3:13: reference
           |case object Apple extends Fruit
           |            ^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  testLSP("local") {
    cleanWorkspace()
    val a = "a/src/main/scala/a/Upstream.scala"
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {}
           |}
           |/$a
           |package a
           |class Upstream {
           |  var x: Int = 0
           |  def run(): Int = {
           |    this.x = 1
           |    var x = 2
           |    if (x > 1) {
           |      val x = 3
           |      val y = x + x
           |    }
           |    x += 1
           |    x
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpenAndFocus(a)
      _ <- server.assertReferencesSubquery(
        a,
        "var x@@ = 2",
        """|a/src/main/scala/a/Upstream.scala:6:9: reference
           |    var x = 2
           |        ^
           |a/src/main/scala/a/Upstream.scala:7:9: reference
           |    if (x > 1) {
           |        ^
           |a/src/main/scala/a/Upstream.scala:11:5: reference
           |    x += 1
           |    ^
           |a/src/main/scala/a/Upstream.scala:12:5: reference
           |    x
           |    ^
           |""".stripMargin,
      )
    } yield ()
  }

  testLSP("method-override") {
    cleanWorkspace()
    val a = "a/src/main/scala/a/Fruit.scala"
    val b = "a/src/main/scala/a/Downstream.scala"
    val c = "a/src/main/scala/a/Berry.scala"
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {}
           |}
           |/$a
           |package a
           |abstract class Fruit {
           |  def color: String
           |}
           |class Apple extends Fruit {
           |  override def color: String = "red"
           |}
           |class TropicalFruit extends Fruit {
           |  override def color: String = "green"
           |}
           |class Banana extends TropicalFruit {
           |  override def color: String = "yellow"
           |}
           |/$c
           |package a
           |class Berry extends Fruit {
           |  override def color: String = "blue"
           |}
           |/$b
           |package a
           |object Downstream {
           |  def main(): Unit = {
           |    val fruit: Fruit = new Apple()
           |    val apple: Apple = new Apple()
           |    val banana: Banana = new Banana()
           |    val berry: Berry = new Berry()
           |    fruit.color
           |    apple.color
           |    banana.color
           |    berry.color
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpenAndFocus(a)
      _ <- server.assertReferencesSubquery(
        a,
        "override def col@@or: String = \"yellow\"",
        """|a/src/main/scala/a/Berry.scala:3:16: reference
           |  override def color: String = "blue"
           |               ^^^^^
           |a/src/main/scala/a/Downstream.scala:8:11: reference
           |    fruit.color
           |          ^^^^^
           |a/src/main/scala/a/Downstream.scala:9:11: reference
           |    apple.color
           |          ^^^^^
           |a/src/main/scala/a/Downstream.scala:10:12: reference
           |    banana.color
           |           ^^^^^
           |a/src/main/scala/a/Downstream.scala:11:11: reference
           |    berry.color
           |          ^^^^^
           |a/src/main/scala/a/Fruit.scala:3:7: reference
           |  def color: String
           |      ^^^^^
           |a/src/main/scala/a/Fruit.scala:6:16: reference
           |  override def color: String = "red"
           |               ^^^^^
           |a/src/main/scala/a/Fruit.scala:9:16: reference
           |  override def color: String = "green"
           |               ^^^^^
           |a/src/main/scala/a/Fruit.scala:12:16: reference
           |  override def color: String = "yellow"
           |               ^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  testLSP("trait-extends") {
    cleanWorkspace()
    val a = "a/src/main/scala/a/Animal.scala"
    val b = "a/src/main/scala/a/Mammal.scala"
    val c = "b/src/main/scala/b/Animal.scala"
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
           |trait Animal {
           |  def color: String = "blue"
           |}
           |/$b
           |package a
           |class Mammal extends Animal {
           |  override def color: String = "brown"
           |}
           |object Mammal {
           |  class Dog extends Mammal {
           |    override def color: String = "golden"
           |  }
           |}
           |/$c
           |package b
           |class Animal {
           |  def color: String = {
           |    val animal = new a.Animal {}
           |    animal.color
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpenAndFocus(a)
      _ <- server.assertReferencesSubquery(
        a,
        "trait Ani@@mal",
        """|a/src/main/scala/a/Animal.scala:2:7: reference
           |trait Animal {
           |      ^^^^^^
           |a/src/main/scala/a/Mammal.scala:2:22: reference
           |class Mammal extends Animal {
           |                     ^^^^^^
           |b/src/main/scala/b/Animal.scala:4:24: reference
           |    val animal = new a.Animal {}
           |                       ^^^^^^
           |""".stripMargin,
      )
      _ <- server.assertReferencesSubquery(
        b,
        "class Do@@g",
        """|a/src/main/scala/a/Mammal.scala:6:9: reference
           |  class Dog extends Mammal {
           |        ^^^
           |""".stripMargin,
      )
    } yield ()
  }

  testLSP("object") {
    cleanWorkspace()
    val a = "a/src/main/scala/a/Utils.scala"
    val b = "b/src/main/scala/b/B.scala"
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
           |object Utils {
           |  def greet(): String = "Hello"
           |  val version: Int = 1
           |}
           |/$b
           |package b
           |import a.Utils
           |object B {
           |  def main(): Unit = {
           |    println(Utils.greet())
           |    println(Utils.version)
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpenAndFocus(a)
      _ <- server.assertReferencesSubquery(
        a,
        "object Uti@@ls",
        """|a/src/main/scala/a/Utils.scala:2:8: reference
           |object Utils {
           |       ^^^^^
           |b/src/main/scala/b/B.scala:2:10: reference
           |import a.Utils
           |         ^^^^^
           |b/src/main/scala/b/B.scala:5:13: reference
           |    println(Utils.greet())
           |            ^^^^^
           |b/src/main/scala/b/B.scala:6:13: reference
           |    println(Utils.version)
           |            ^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  testLSP("implicit-class") {
    cleanWorkspace()
    val a = "a/src/main/scala/a/Extensions.scala"
    val b = "a/src/main/scala/a/Downstream.scala"
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {}
           |}
           |/$a
           |package a
           |object Extensions {
           |  implicit class StringOps(s: String) {
           |    def shout: String = s.toUpperCase + "!"
           |  }
           |}
           |/$b
           |package a
           |import Extensions._
           |object Downstream {
           |  def main(): Unit = {
           |    val result = "hello".shout
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpenAndFocus(a)
      _ <- server.assertReferencesSubquery(
        a,
        "def sho@@ut",
        """|a/src/main/scala/a/Downstream.scala:5:26: reference
           |    val result = "hello".shout
           |                         ^^^^^
           |a/src/main/scala/a/Extensions.scala:4:9: reference
           |    def shout: String = s.toUpperCase + "!"
           |        ^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  testLSP("type-alias") {
    cleanWorkspace()
    val a = "a/src/main/scala/a/Types.scala"
    val b = "a/src/main/scala/a/Downstream.scala"
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {}
           |}
           |/$a
           |package a
           |object Types {
           |  type StringMap[A] = Map[String, A]
           |}
           |/$b
           |package a
           |import Types._
           |object Downstream {
           |  val m: StringMap[Int] = Map("one" -> 1)
           |}
           |""".stripMargin
      )
      _ <- server.didOpenAndFocus(a)
      _ <- server.assertReferencesSubquery(
        a,
        "type String@@Map",
        """|a/src/main/scala/a/Downstream.scala:4:10: reference
           |  val m: StringMap[Int] = Map("one" -> 1)
           |         ^^^^^^^^^
           |a/src/main/scala/a/Types.scala:3:8: reference
           |  type StringMap[A] = Map[String, A]
           |       ^^^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  // this works in synced files
  testLSP("renamed-import") {
    cleanWorkspace()
    val a = "a/src/main/scala/a/Utils.scala"
    val b = "b/src/main/scala/b/B.scala"
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
           |object Utils {
           |  def greet(): String = "Hello"
           |  val version: Int = 1
           |}
           |/$b
           |package b
           |import a.Utils.{greet => hello, version => ver}
           |object B {
           |  def main(): Unit = {
           |    println(hello())
           |    println(ver)
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpenAndFocus(a)
      // references to a renamed method import
      _ <- server.assertReferencesSubquery(
        a,
        "def gre@@et",
        """|a/src/main/scala/a/Utils.scala:3:7: reference
           |  def greet(): String = "Hello"
           |      ^^^^^
           |b/src/main/scala/b/B.scala:2:17: reference
           |import a.Utils.{greet => hello, version => ver}
           |                ^^^^^
           |b/src/main/scala/b/B.scala:5:13: reference
           |    println(hello())
           |            ^^^^^
           |""".stripMargin,
      )
      // references to a renamed val import
      _ <- server.assertReferencesSubquery(
        a,
        "val vers@@ion",
        """|a/src/main/scala/a/Utils.scala:4:7: reference
           |  val version: Int = 1
           |      ^^^^^^^
           |b/src/main/scala/b/B.scala:2:33: reference
           |import a.Utils.{greet => hello, version => ver}
           |                                ^^^^^^^
           |b/src/main/scala/b/B.scala:6:13: reference
           |    println(ver)
           |            ^^^
           |""".stripMargin,
      )
    } yield ()
  }

  // Find references to Java methods/fields from Scala code
  testLSP("scala-to-java-references") {
    cleanWorkspace()
    val javaFile = "a/src/main/java/a/JavaUtils.java"
    val scalaFile = "a/src/main/scala/a/ScalaUser.scala"
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {}
           |}
           |/$javaFile
           |package a;
           |public class JavaUtils {
           |  public static String greet() {
           |    return "Hello from Java";
           |  }
           |  public static int VERSION = 42;
           |}
           |/$scalaFile
           |package a
           |object ScalaUser {
           |  def main(): Unit = {
           |    println(JavaUtils.greet())
           |    println(JavaUtils.VERSION)
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpenAndFocus(javaFile)
      // references to Java method from Scala
      _ <- server.assertReferencesSubquery(
        javaFile,
        "public static String gre@@et()",
        """|a/src/main/java/a/JavaUtils.java:3:24: reference
           |  public static String greet() {
           |                       ^^^^^
           |a/src/main/scala/a/ScalaUser.scala:4:23: reference
           |    println(JavaUtils.greet())
           |                      ^^^^^
           |""".stripMargin,
      )
      // references to Java field from Scala
      _ <- server.assertReferencesSubquery(
        javaFile,
        "public static int VER@@SION",
        """|a/src/main/java/a/JavaUtils.java:6:21: reference
           |  public static int VERSION = 42;
           |                    ^^^^^^^
           |a/src/main/scala/a/ScalaUser.scala:5:23: reference
           |    println(JavaUtils.VERSION)
           |                      ^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  // Find references to Scala methods/vals from Java code
  testLSP("java-to-scala-references") {
    cleanWorkspace()
    val scalaFile = "a/src/main/scala/a/ScalaUtils.scala"
    val javaFile = "a/src/main/java/a/JavaUser.java"
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {}
           |}
           |/$scalaFile
           |package a
           |object ScalaUtils {
           |  def hello(): String = "Hello from Scala"
           |  val count: Int = 10
           |}
           |/$javaFile
           |package a;
           |public class JavaUser {
           |  public void run() {
           |    System.out.println(ScalaUtils.hello());
           |    System.out.println(ScalaUtils.count());
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpenAndFocus(scalaFile)
      // references to Scala method from Java
      _ <- server.assertReferencesSubquery(
        scalaFile,
        "def hel@@lo()",
        """|a/src/main/java/a/JavaUser.java:4:35: reference
           |    System.out.println(ScalaUtils.hello());
           |                                  ^^^^^
           |a/src/main/scala/a/ScalaUtils.scala:3:7: reference
           |  def hello(): String = "Hello from Scala"
           |      ^^^^^
           |""".stripMargin,
      )
      // references to Scala val from Java
      _ <- server.assertReferencesSubquery(
        scalaFile,
        "val cou@@nt",
        """|a/src/main/java/a/JavaUser.java:5:35: reference
           |    System.out.println(ScalaUtils.count());
           |                                  ^^^^^
           |a/src/main/scala/a/ScalaUtils.scala:4:7: reference
           |  val count: Int = 10
           |      ^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  // ignore for now, this doesn't work yet
  testLSP("java-to-scala-references-no-forwarder".ignore) {
    cleanWorkspace()
    val scalaFile = "a/src/main/scala/a/ScalaUtils.scala"
    val javaFile = "a/src/main/java/a/JavaUser.java"
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {}
           |}
           |/$scalaFile
           |package a
           |object ScalaUtils {
           |  def hello(): String = "Hello from Scala object"
           |  val count: Int = 10
           |}
           |class ScalaUtils {
           |  def hello(): String = "Hello from Scala class"
           |  val count: Int = 12
           |}
           |/$javaFile
           |package a;
           |public class JavaUser {
           |  public void run() {
           |    System.out.println(ScalaUtils$$.MODULE$$.hello());
           |    System.out.println(ScalaUtils$$.MODULE$$.count());
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpenAndFocus(scalaFile)
      // references to Scala method from Java
      _ <- server.assertReferencesSubquery(
        scalaFile,
        "def he@@llo(): String = \"Hello from Scala object\"",
        """|a/src/main/java/a/JavaUser.java:4:35: reference
           |    System.out.println(ScalaUtils$$.MODULE$$.hello());
           |                                             ^^^^^
           |a/src/main/scala/a/ScalaUtils.scala:3:7: reference
           |  def hello(): String = "Hello from Scala object"
           |      ^^^^^
           |""".stripMargin,
      )
      // references to Scala val from Java
      _ <- server.assertReferencesSubquery(
        scalaFile,
        "val cou@@nt: Int = 12",
        """|a/src/main/java/a/JavaUser.java:5:35: reference
           |    System.out.println(ScalaUtils$$.MODULE$$.count());
           |                                             ^^^^^
           |a/src/main/scala/a/ScalaUtils.scala:4:7: reference
           |  val count: Int = 12
           |      ^^^^^
           |""".stripMargin,
      )
    } yield ()
  }
}
