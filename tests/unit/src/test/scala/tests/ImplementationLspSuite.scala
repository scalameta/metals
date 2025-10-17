package tests

class ImplementationLspSuite extends BaseImplementationSuite("implementation") {

  check(
    "basic",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |trait Livin@@gBeing
       |abstract class <<Animal>> extends LivingBeing
       |class <<Dog>> extends Animal
       |class <<Cat>> extends Animal
       |""".stripMargin,
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
       |/a/src/main/scala/a/Human.scala
       |package a
       |object Human {
       |  val person = new <<>>LivingBeing {}
       |}
       |""".stripMargin,
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
       |""".stripMargin,
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
       |""".stripMargin,
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
       |""".stripMargin,
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
       |""".stripMargin,
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
       |""".stripMargin,
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
       |""".stripMargin,
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
       |""".stripMargin,
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
       |""".stripMargin,
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
       |""".stripMargin,
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
       |""".stripMargin,
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
       |""".stripMargin,
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
       |""".stripMargin,
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
       |""".stripMargin,
  )

  check(
    "java-classes",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |class <<MyException>> extends Exce@@ption
       |class <<NewException>> extends RuntimeException
       |class <<NewException2>> extends RuntimeException
       |""".stripMargin,
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
       |""".stripMargin,
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
       |""".stripMargin,
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
       |""".stripMargin,
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
       |""".stripMargin,
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
       |""".stripMargin,
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
       |""".stripMargin,
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
       |""".stripMargin,
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
       |""".stripMargin,
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
       |""".stripMargin,
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
       |""".stripMargin,
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
       |""".stripMargin,
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
       |""".stripMargin,
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
       |""".stripMargin,
  )

  check(
    "libraries",
    """|/a/src/main/scala/a/A.scala
       |package a
       |
       |import org.scalatest.BeforeAndAfterAll
       |import org.scalatest.matchers.should.Matchers
       |import org.scalatest.wordspec.AnyWordSpec
       |
       |class <<ZigZagTest>> extends AnyWordSpec with Matchers with Before@@AndAfterAll {}
       |class <<ZigZagTest2>> extends AnyWordSpec with Matchers with BeforeAndAfterAll {}
       |class <<ZigZagTest3>> extends AnyWordSpec with Matchers with BeforeAndAfterAll {}
       |""".stripMargin,
  )

  check(
    "anon",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |trait A@@nimal
       |object Main{
       |  val animal = new <<>>Animal{ def field(d : String) : Int = 123 }
       |}
       |""".stripMargin,
  )

  check(
    "anon-method",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |trait Animal{ def soun@@d : String}
       |object Main{
       |  val animal = new Animal{ def <<sound>> = "|unknown|" }
       |}
       |""".stripMargin,
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
       |""".stripMargin,
    additionalLibraryDependencies =
      List("io.circe::circe-generic-extras:0.14.0"),
    scalacOptions = List("-Ymacro-annotations"),
  )

  check(
    "type-local",
    """|/a/src/main/scala/a/Main.scala
       |object Test {
       |  def main {
       |    class @@A
       |    class <<B>> extends A
       |    type C = A
       |    class <<D>> extends C
       |  }
       |}
       |""".stripMargin,
  )

  check(
    "local-methods",
    """|/a/src/main/scala/a/Main.scala
       |object Test {
       |  def main {
       |    trait A {
       |      def f@@oo(): Int
       |    }
       |    class B extends A {
       |      def <<foo>>(): Int = 1
       |    }
       |  }
       |}
       |""".stripMargin,
  )

  check(
    "type-implementation",
    """|/a/src/main/scala/a/Main.scala
       |trait Test {
       |  type C@@C 
       |}
       |
       |trait Other extends Test {
       |  type <<CC>> = String
       |}
       |
       |trait Unrelated
       |""".stripMargin,
  )

  check(
    "java-implementation",
    """|/a/src/main/scala/a/Main.scala
       |// empty scala file, so Scala pc is loaded
       |/a/src/main/scala/a/Main.java
       |package a;
       |public class Main {
       |  abstract class A {
       |      abstract void he@@llo();
       |  }
       |  abstract class B extends A{
       |      @Override void <<hello>>(){
       |          System.out.println("Hello!");
       |      }
       |  }
       |  class C extends B{
       |      @Override void <<hello>>(){
       |          System.out.println("Bye!");
       |      }
       |  }
       |}
       |""".stripMargin,
  )

  check(
    "java-scala-implementation",
    """|/a/src/main/scala/a/Test.scala
       |package a
       |class <<Test>> extends TestJava with TestScala {}
       |trait TestScala {}
       |/a/src/main/java/a/TestJava.java
       |package a;
       |public interface TestJ@@ava {}
       |""".stripMargin,
  )

  checkSymbols(
    "set",
    """|package a
       |class MySet[A] extends S@@et[A] {
       |  override def iterator: Iterator[A] = ???
       |  override def contains(elem: A): Boolean = ???
       |  override def incl(elem: A): Set[A] = ???
       |  override def excl(elem: A): Set[A] = ???
       |}
       |""".stripMargin,
    """|a/MySet#
       |scala/Enumeration#ValueSet#
       |scala/collection/immutable/AbstractSet#
       |scala/collection/immutable/BitSet#
       |scala/collection/immutable/BitSet.BitSet1#
       |scala/collection/immutable/BitSet.BitSet2#
       |scala/collection/immutable/BitSet.BitSetN#
       |scala/collection/immutable/HashMap#HashKeySet#
       |scala/collection/immutable/HashSet#
       |scala/collection/immutable/ListSet#
       |scala/collection/immutable/ListSet#Node#
       |scala/collection/immutable/ListSet.EmptyListSet.
       |scala/collection/immutable/MapOps#ImmutableKeySet#
       |scala/collection/immutable/Set.EmptySet.
       |scala/collection/immutable/Set.Set1#
       |scala/collection/immutable/Set.Set2#
       |scala/collection/immutable/Set.Set3#
       |scala/collection/immutable/Set.Set4#
       |scala/collection/immutable/SortedMapOps#ImmutableKeySortedSet#
       |scala/collection/immutable/SortedSet#
       |scala/collection/immutable/TreeSet#
       |""".stripMargin,
  )

  if (isJava21) {
    checkSymbols(
      "exception",
      """package a
        |class MyException extends Excep@@tion
        |""".stripMargin,
      """|a/MyException#
         |com/sun/beans/finder/SignatureException#
         |com/sun/jdi/AbsentInformationException#
         |com/sun/jdi/ClassNotLoadedException#
         |com/sun/jdi/ClassNotPreparedException#
         |com/sun/jdi/IncompatibleThreadStateException#
         |com/sun/jdi/InconsistentDebugInfoException#
         |com/sun/jdi/InternalException#
         |com/sun/jdi/InvalidCodeIndexException#
         |com/sun/jdi/InvalidLineNumberException#
         |com/sun/jdi/InvalidModuleException#
         |com/sun/jdi/InvalidStackFrameException#
         |com/sun/jdi/InvalidTypeException#
         |com/sun/jdi/InvocationException#
         |com/sun/jdi/NativeMethodException#
         |com/sun/jdi/ObjectCollectedException#
         |com/sun/jdi/OpaqueFrameException#
         |com/sun/jdi/VMCannotBeModifiedException#
         |com/sun/jdi/VMDisconnectedException#
         |com/sun/jdi/VMMismatchException#
         |com/sun/jdi/VMOutOfMemoryException#
         |com/sun/jdi/connect/IllegalConnectorArgumentsException#
         |com/sun/jdi/connect/TransportTimeoutException#
         |com/sun/jdi/connect/VMStartException#
         |com/sun/jdi/connect/spi/ClosedConnectionException#
         |com/sun/jdi/request/DuplicateRequestException#
         |com/sun/jdi/request/InvalidRequestStateException#
         |com/sun/jndi/ldap/LdapReferralException#
         |com/sun/media/sound/InvalidDataException#
         |com/sun/media/sound/InvalidFormatException#
         |com/sun/media/sound/RIFFInvalidDataException#
         |com/sun/media/sound/RIFFInvalidFormatException#
         |com/sun/nio/sctp/IllegalReceiveException#
         |com/sun/nio/sctp/IllegalUnbindException#
         |com/sun/nio/sctp/InvalidStreamException#
         |com/sun/org/apache/bcel/internal/classfile/ClassFormatException#
         |com/sun/org/apache/bcel/internal/generic/ClassGenException#
         |com/sun/org/apache/bcel/internal/generic/TargetLostException#
         |com/sun/org/apache/xalan/internal/xsltc/TransletException#
         |com/sun/org/apache/xalan/internal/xsltc/compiler/CompilerException#
         |com/sun/org/apache/xalan/internal/xsltc/compiler/IllegalCharException#
         |com/sun/org/apache/xalan/internal/xsltc/compiler/util/TypeCheckError#
         |com/sun/org/apache/xerces/internal/dom/AbortException#
         |com/sun/org/apache/xerces/internal/dom/RangeExceptionImpl#
         |com/sun/org/apache/xerces/internal/impl/dv/DVFactoryException#
         |com/sun/org/apache/xerces/internal/impl/dv/DatatypeException#
         |com/sun/org/apache/xerces/internal/impl/dv/InvalidDatatypeFacetException#
         |com/sun/org/apache/xerces/internal/impl/dv/InvalidDatatypeValueException#
         |com/sun/org/apache/xerces/internal/impl/dv/xs/SchemaDateTimeException#
         |com/sun/org/apache/xerces/internal/impl/io/MalformedByteSequenceException#
         |""".stripMargin,
      topLines = Some(50),
    )
  }

  check(
    "multi-module",
    """|/a/src/main/scala/com/example/foo/Foo.scala
       |package com.example.foo
       |trait F@@oo {
       |  def transform(input: Int): Int
       |}
       |/b/src/main/scala/com/example/bar/Bar.scala
       |package com.example.bar
       |
       |import com.example.foo.Foo
       |
       |class <<Bar>> extends Foo {
       |  override def transform(input: Int): Int = input * 2
       |}
       |""".stripMargin,
    customMetalsJson = Some(
      """|{
         |  "a":{ },
         |  "b":{
         |    "dependsOn": ["a"]
         |  }
         |}
         |""".stripMargin
    ),
  )

  check(
    "self-type",
    """|/a/src/main/scala/a/Main.scala
       |trait A { def a@@a: Unit }
       |trait B {
       | this : A =>
       |  override def <<aa>>: Unit = ()
       |}
       |""".stripMargin,
  )

  check(
    "self-type-1",
    """|/a/src/main/scala/a/Main.scala
       |trait A {
       |  def aa(i: Int): String = ""
       |  def a@@a: Unit
       |}
       |trait B {
       | this : A =>
       |  override def <<aa>>: Unit = ()
       |}
       |""".stripMargin,
  )

  check(
    "self-type-with",
    """|/a/src/main/scala/a/Main.scala
       |trait C
       |trait A { def a@@a: Unit }
       |trait B {
       | this : A with C =>
       |  override def <<aa>>: Unit = ()
       |}
       |""".stripMargin,
  )

  checkSymbols(
    "self-type-in-lib",
    """|trait A extends Ite@@rable[_]
       |""".stripMargin,
    "scala/collection/generic/DefaultSerializable#",
    filter = _.contains("DefaultSerializable"),
    maxRetry = 3,
  )

  override protected def libraryDependencies: List[String] =
    List("org.scalatest::scalatest:3.2.16", "io.circe::circe-generic:0.12.0")

}
