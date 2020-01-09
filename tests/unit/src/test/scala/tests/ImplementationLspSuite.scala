package tests
import scala.concurrent.Future
import funsuite.Location

class ImplementationLspSuite extends BaseLspSuite("implementation") {

  check(
    "basic",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |trait Livin@@gBeing
       |abstract class <<Animal>> extends LivingBeing
       |class <<Dog>> extends Animal
       |class <<Cat>> extends Animal
       |""".stripMargin
  )

  check(
    "advanced",
    """|/a/src/main/scala/a/LivingBeing.scala
       |package a
       |trait Livin@@gBeing
       |/a/src/main/scala/a/MadeOfAtoms.scala
       |package a
       |trait <<MadeOfAtoms>> extends LivingBeing
       |/a/src/main/scala/a/Animal.scala
       |package a
       |abstract class <<Animal>> extends LivingBeing
       |/a/src/main/scala/a/Dog.scala
       |package a
       |class <<Dog>> extends Animal with MadeOfAtoms
       |/a/src/main/scala/a/Cat.scala
       |package a
       |class <<Cat>> extends Animal
       |""".stripMargin
  )

  check(
    "inside-object",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |trait LivingBeing
       |abstract class Ani@@mal extends LivingBeing
       |object outer{
       |  object inner{
       |    class <<Dog>> extends Animal
       |    class <<Cat>> extends Animal
       |    class Unrelated extends LivingBeing
       |  }
       |}
       |""".stripMargin
  )

  check(
    "file-locals",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |trait Ap@@p
       |object outer{
       |  abstract class <<State>> extends App
       |  val app = new <<>>State{}
       |}
       |/a/src/main/scala/a/Other.scala
       |package a
       |object Other{
       |  def main(){
       |    trait Inner
       |    object InnerImpl extends Inner
       |  }
       |}
       |""".stripMargin
  )

  check(
    "basic-value",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |trait Math{
       |  def ze@@ro: Double
       |}
       |object WeirdMath extends Math{
       |  val <<zero>> = -1.0
       |}
       |""".stripMargin
  )

  check(
    "basic-var",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |trait Math{
       |  def ze@@ro: Double
       |}
       |object WeirdMath extends Math{
       |  var <<zero>> = -1.0
       |}
       |""".stripMargin
  )

  check(
    "nested",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |trait Math{
       |  def ze@@ro: Double
       |}
       |class Universe{
       |  object WeirdMath extends Math{
       |    def <<zero>> = {
       |      val a = 1.1
       |      val b = 3.2
       |      a + b
       |    }
       |  }
       |}
       |""".stripMargin
  )

  check(
    "basic-method",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |trait LivingBeing{
       |  def s@@ound: String
       |}
       |abstract class Animal extends LivingBeing{}
       |class Dog extends Animal{
       |  def <<sound>> = "woof"
       |  def other = 123
       |}
       |class Cat extends Animal{
       |  override def <<sound>> = "woof"
       |  def another(str : Long) = 123
       |}
       |""".stripMargin
  )

  check(
    "basic-method-params",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |trait LivingBeing{
       |  def sound: Int
       |  def s@@ound(times : Int): Int = 1
       |  def sound(start : Long): Int =  1
       |}
       |abstract class Animal extends LivingBeing{}
       |class Dog extends Animal{
       |  def sound = 1
       |  override def sound(times : Long) = 1
       |  override def <<sound>>(times : Int) = 1
       |}
       |class Cat extends Animal{
       |  override def <<sound>>(times : Int) = 1
       |  override def sound = 1
       |}
       |""".stripMargin
  )

  check(
    "on-usage",
    """|/a/src/main/scala/a/Animals.scala
       |package a
       |trait Animal{
       |  def sound: Int
       |}
       |class Dog extends Animal{
       |  def <<sound>> = 1
       |}
       |class Cat extends Animal{
       |  def <<sound>> = 2
       |}
       |/a/src/main/scala/a/Main.scala
       |package a
       |object Main{
       |  val animal : Animal = new Dog()
       |  animal.sou@@nd
       |}
       |""".stripMargin
  )

  check(
    "long-method-params",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |trait A{
       |  def z@@ero(a : Int, b : Option[String])(c : Long, d: Double): Double = 0.0
       |  def zero(a : Long, b : Option[String])(c : Long, d: Double): Double = 0.0
       |}
       |class B extends A{
       |  override def zero(a : Long, b : Option[String])(c : Long, d: Double): Double = 0.6
       |  override def <<zero>>(a : Int, b : Option[String])(c : Long, d: Double): Double = 0.5
       |}
       |""".stripMargin
  )

  check(
    "generic-method",
    """|/a/src/main/scala/a/Main.scala
       |trait LivingObject {
       |  def so@@und[T](t: T): T
       |}
       |abstract class Animal extends LivingObject
       |object Cat extends Animal {
       |  override def <<sound>>[O](t: O): O = t
       |}
       |""".stripMargin
  )

  check(
    "generic-impl",
    """|/a/src/main/scala/a/Main.scala
       |trait Math[T] {
       |  def zer@@o(t: T): T
       |}
       |object IntegerMath extends Math[Int] {
       |  override def <<zero>>(t: Int): Int = 0
       |}
       |""".stripMargin
  )

  check(
    "generic-impl-type",
    """|/a/src/main/scala/a/Main.scala
       |trait Math[T] {
       |  def zer@@o(t: T): T
       |}
       |object IntegerMath extends Math[Int] {
       |  type P = Int
       |  type T = Double
       |  def <<zero>>(t: P): P = 0
       |  def zero(t: T): T = 0
       |}
       |""".stripMargin
  )

  check(
    "generic-advanced",
    """|/a/src/main/scala/a/A.scala
       |trait A[S, R, T] {
       |  def meth@@od(s: S, r: R, t: T): T
       |}
       |/a/src/main/scala/a/B.scala
       |trait B[O] extends A[Int, O, Double]{
       |  def <<method>>(s: Int, r: O, t: Double): Double = ???
       |}
       |/a/src/main/scala/a/C.scala
       |class C extends B[Long] {
       |  override def <<method>>(s: Int, r: Long, t: Double): Double = ???
       |}
       |""".stripMargin
  )

  check(
    "java-classes",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |class <<MyException>> extends Exce@@ption
       |class <<NewException>> extends RuntimeException
       |class <<NewException2>> extends RuntimeException
       |""".stripMargin
  )

  check(
    "local-classes",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |object Main{
       |  trait Fo@@od
       |  type Eatable = Food
       |  type Tasty = Eatable
       |  class <<Pizza>> extends Tasty
       |}
       |""".stripMargin
  )

  check(
    "lib-type",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |object Main {
       |  type T = S@@eq[Int]
       |  class <<ABC>> extends T {
       |    def apply(idx: Int): Int = ???
       |    def iterator: Iterator[Int] = ???
       |    def length: Int = ???
       |  }
       |}
       |""".stripMargin
  )

  check(
    "lib-alias",
    """|/a/src/main/scala/a/A.scala
       |package a
       |trait A {
       |  def met@@hod(ex: java.lang.Exception) = 0
       |}
       |class B extends A {
       |  override def <<method>>(ex: Exception): Int = 1
       |}
       |""".stripMargin
  )

  check(
    "type-alias-global",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |object Main {
       |  type NewSeqInt[T <: Int] = Seq[T]
       |  type NewSeq = NewSeqInt[Int]
       |  class <<ABC>> extends New@@Seq {
       |    def apply(idx: Int): Int = ???
       |    def iterator: Iterator[Int] = ???
       |    def length: Int = ???
       |  }
       |}
       |""".stripMargin
  )

  check(
    "unrelated-invo",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |object Main {
       |  trait A {
       |    def str: String
       |  }
       |  trait B extends A {}
       |  class C extends B {
       |    def <<str>> = ""
       |  }
       |  val b: B = new C
       |  b.st@@r
       |}
       |""".stripMargin
  )

  check(
    "local-advanced",
    """|/a/src/main/scala/a/Parent.scala
       |package a
       |class Parent
       |/a/src/main/scala/a/A.scala
       |package a
       |object A {
       |  type Adult = Parent
       |}
       |/a/src/main/scala/a/B.scala
       |package a
       |object B {
       |  type O@@ld = A.Adult
       |
       |}
       |/a/src/main/scala/a/Responsible.scala
       |package a
       |class <<Responsible>> extends B.Old
       |class <<Other>> extends Parent
       |""".stripMargin
  )

  check(
    "local-type-alias",
    """|/a/src/main/scala/a/Parent.scala
       |package a
       |class Parent{
       |  def m@@ethod(a : Parent.Name) = "<adult>"
       |}
       |object Parent{
       |  type Name = String
       |}
       |/a/src/main/scala/a/Names.scala
       |package a
       |object Names {
       |  type Basic = String
       |}
       |/a/src/main/scala/a/Father.scala
       |package a
       |class Father extends Parent {
       |  override def <<method>>(a : Names.Basic) = "<father>"
       |}
       |""".stripMargin
  )

  check(
    "type-with-params",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |object Main {
       |  trait Two[A, B] {
       |    def a@@b(a: A): B
       |  }
       |  type StringTwo[A] = Two[String, A]
       |  class StringStringTwo extends StringTwo[String] {
       |    def <<ab>>(a: String) = ""
       |  }
       |}
       |""".stripMargin
  )

  check(
    "higher-kinded",
    """|/a/src/main/scala/a/Higher.scala
       |package a
       |trait Higher[F[_]] {
       |  def fun@@c(a: F[_])
       |}
       |/a/src/main/scala/a/HigherList.scala
       |package a
       |class HigherList extends Higher[List] {
       |  def <<func>>(a: List[_]): Unit = ???
       |}
       |""".stripMargin
  )

  check(
    "vararg",
    """|/a/src/main/scala/a/A.scala
       |package a
       |trait A {
       |  def method(a: String): Unit
       |  def me@@thod(a: String*): Unit
       |}
       |class B extends A {
       |  def method(a: String): Unit = ???
       |  def <<method>>(a: String*): Unit = ???
       |}
       |""".stripMargin
  )

  check(
    "structural",
    """|/a/src/main/scala/a/A.scala
       |package a
       |trait A {
       |  def me@@thod(a: String with Int): Unit
       |}
       |class B extends A {
       |  def <<method>>(a: String with Int): Unit = ???
       |}
       |""".stripMargin
  )

  check(
    "by-name",
    """|/a/src/main/scala/a/A.scala
       |package a
       |trait A {
       |  def me@@thod(a: => String): Unit
       |}
       |class B extends A {
       |  def <<method>>(a: => String ): Unit = ???
       |}
       |""".stripMargin
  )

  check(
    "object",
    """|/a/src/main/scala/a/A.scala
       |package a
       |trait A {
       |  def me@@thod(param: a.C.type): Unit
       |}
       |class B extends A {
       |  def <<method>>(param: a.C.type): Unit = ???
       |}
       |object C
       |""".stripMargin
  )

  check(
    "libraries",
    """|/a/src/main/scala/a/A.scala
       |package a
       |import org.scalatest.FunSuite
       |import org.scalatest.WordSpecLike
       |import org.scalatest.Matchers
       |import org.scalatest.BeforeAndAfterAll
       |
       |class <<ZigZagTest>> extends WordSpecLike with Matchers with Before@@AndAfterAll {}
       |class <<ZigZagTest2>> extends WordSpecLike with Matchers with BeforeAndAfterAll {}
       |class <<ZigZagTest3>> extends WordSpecLike with Matchers with BeforeAndAfterAll {}
       |""".stripMargin
  )

  check(
    "anon",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |trait A@@nimal
       |object Main{
       |  val animal = new <<>>Animal{ def field(d : String) : Int = 123 }
       |}
       |""".stripMargin
  )

  check(
    "anon-method",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |trait Animal{ def soun@@d : String}
       |object Main{
       |  val animal = new Animal{ def <<sound>> = "|unknown|" }
       |}
       |""".stripMargin
  )

  check(
    "macro-annotation",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |import io.circe.generic.JsonCodec
       |trait Living@@Being
       |@JsonCodec sealed trait <<Animal>> extends LivingBeing
       |object Animal {
       |  case object <<Dog>> extends Animal
       |  case object <<Cat>> extends Animal
       |}
       |""".stripMargin
  )

  def check(name: String, input: String)(implicit loc: Location): Unit = {
    val files = FileLayout.mapFromString(input)
    val (filename, edit) = files
      .find(_._2.contains("@@"))
      .map {
        case (fileName, code) =>
          (fileName, code.replaceAll("(<<|>>)", ""))
      }
      .getOrElse {
        throw new IllegalArgumentException(
          "No `@@` was defined that specifies cursor position"
        )
      }
    val expected = files.map {
      case (fileName, code) =>
        fileName -> code.replaceAll("@@", "")
    }
    val base = files.map {
      case (fileName, code) =>
        fileName -> code.replaceAll("(<<|>>|@@)", "")
    }

    testAsync(name) {
      cleanWorkspace()
      for {
        _ <- server.initialize(
          s"""/metals.json
             |{"a":
             |  {
             |    "compilerPlugins": [
             |      "org.scalamacros:::paradise:2.1.1"
             |    ],
             |    "libraryDependencies": [
             |      "org.scalatest::scalatest:3.0.5",
             |      "io.circe::circe-generic:0.12.0"
             |    ]
             |  }
             |}
             |${input
               .replaceAll("(<<|>>|@@)", "")}""".stripMargin
        )
        _ <- Future.sequence(
          files.map(file => server.didOpen(s"${file._1}"))
        )
        _ <- server.assertImplementation(
          filename,
          edit,
          expected.toMap,
          base.toMap
        )
      } yield ()
    }
  }
}
