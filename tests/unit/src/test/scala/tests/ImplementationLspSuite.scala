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

  if (!isJava8) {
    checkSymbols(
      "exception",
      """package a
        |class MyException extends Excep@@tion
        |""".stripMargin,
      """|a/MyException#
         |com/sun/beans/finder/SignatureException#
         |com/sun/imageio/plugins/jpeg/JFIFMarkerSegment#IllegalThumbException#
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
         |com/sun/org/apache/xerces/internal/impl/xpath/XPathException#
         |com/sun/org/apache/xerces/internal/impl/xpath/regex/ParseException#
         |com/sun/org/apache/xerces/internal/impl/xs/XMLSchemaException#
         |com/sun/org/apache/xerces/internal/jaxp/validation/WrappedSAXException#
         |com/sun/org/apache/xerces/internal/xni/XNIException#
         |com/sun/org/apache/xerces/internal/xni/parser/XMLConfigurationException#
         |com/sun/org/apache/xerces/internal/xni/parser/XMLParseException#
         |com/sun/org/apache/xerces/internal/xs/XSException#
         |com/sun/org/apache/xml/internal/dtm/DTMDOMException#
         |com/sun/org/apache/xml/internal/dtm/DTMException#
         |com/sun/org/apache/xml/internal/dtm/ref/DTMNamedNodeMap#DTMException#
         |com/sun/org/apache/xml/internal/dtm/ref/IncrementalSAXSource_Filter#StopException#
         |com/sun/org/apache/xml/internal/security/c14n/CanonicalizationException#
         |com/sun/org/apache/xml/internal/security/c14n/InvalidCanonicalizerException#
         |com/sun/org/apache/xml/internal/security/exceptions/AlgorithmAlreadyRegisteredException#
         |com/sun/org/apache/xml/internal/security/exceptions/Base64DecodingException#
         |com/sun/org/apache/xml/internal/security/exceptions/XMLSecurityException#
         |com/sun/org/apache/xml/internal/security/keys/keyresolver/KeyResolverException#
         |com/sun/org/apache/xml/internal/security/keys/storage/StorageResolverException#
         |com/sun/org/apache/xml/internal/security/parser/XMLParserException#
         |com/sun/org/apache/xml/internal/security/signature/InvalidDigestValueException#
         |com/sun/org/apache/xml/internal/security/signature/InvalidSignatureValueException#
         |com/sun/org/apache/xml/internal/security/signature/MissingResourceFailureException#
         |com/sun/org/apache/xml/internal/security/signature/ReferenceNotInitializedException#
         |com/sun/org/apache/xml/internal/security/signature/XMLSignatureException#
         |com/sun/org/apache/xml/internal/security/transforms/InvalidTransformException#
         |com/sun/org/apache/xml/internal/security/transforms/TransformationException#
         |com/sun/org/apache/xml/internal/security/utils/resolver/ResourceResolverException#
         |com/sun/org/apache/xml/internal/serializer/utils/WrappedRuntimeException#
         |com/sun/org/apache/xml/internal/utils/StopParseException#
         |com/sun/org/apache/xml/internal/utils/WrappedRuntimeException#
         |com/sun/org/apache/xml/internal/utils/WrongParserException#
         |com/sun/org/apache/xpath/internal/FoundIndex#
         |com/sun/org/apache/xpath/internal/XPathException#
         |com/sun/org/apache/xpath/internal/XPathProcessorException#
         |com/sun/org/apache/xpath/internal/functions/WrongNumberArgsException#
         |com/sun/security/ntlm/NTLMException#
         |com/sun/tools/attach/AgentInitializationException#
         |com/sun/tools/attach/AgentLoadException#
         |com/sun/tools/attach/AttachNotSupportedException#
         |com/sun/tools/attach/AttachOperationFailedException#
         |com/sun/tools/classfile/AttributeException#
         |com/sun/tools/classfile/ConstantPoolException#
         |com/sun/tools/classfile/DescriptorException#
         |com/sun/tools/example/debug/expr/ParseException#
         |com/sun/tools/example/debug/tty/AmbiguousMethodException#
         |com/sun/tools/example/debug/tty/LineNotFoundException#
         |com/sun/tools/example/debug/tty/MalformedMemberNameException#
         |com/sun/tools/example/debug/tty/VMNotConnectedException#
         |com/sun/tools/javac/launcher/Main#Fault#
         |com/sun/tools/javac/util/ClientCodeException#
         |com/sun/tools/javac/util/PropagatedException#
         |com/sun/tools/javap/JavapTask#BadArgs#
         |com/sun/tools/jdeprscan/CSVParseException#
         |com/sun/tools/jdeps/MultiReleaseException#
         |com/sun/tools/jdi/JDWPException#
         |com/sun/tools/sjavac/ProblemException#
         |com/sun/tools/sjavac/client/PortFileInaccessibleException#
         |java/awt/AWTException#
         |java/awt/FontFormatException#
         |java/awt/HeadlessException#
         |java/awt/IllegalComponentStateException#
         |java/awt/color/CMMException#
         |java/awt/color/ProfileDataException#
         |java/awt/datatransfer/MimeTypeParseException#
         |java/awt/datatransfer/UnsupportedFlavorException#
         |java/awt/dnd/InvalidDnDOperationException#
         |java/awt/geom/IllegalPathStateException#
         |java/awt/geom/NoninvertibleTransformException#
         |java/awt/image/ImagingOpException#
         |java/awt/image/RasterFormatException#
         |java/awt/print/PrinterAbortException#
         |java/awt/print/PrinterException#
         |java/awt/print/PrinterIOException#
         |java/beans/IntrospectionException#
         |java/beans/PropertyVetoException#
         |java/io/CharConversionException#
         |java/io/EOFException#
         |java/io/FileNotFoundException#
         |java/io/IOException#
         |java/io/InterruptedIOException#
         |java/io/InvalidClassException#
         |java/io/InvalidObjectException#
         |java/io/NotActiveException#
         |java/io/NotSerializableException#
         |java/io/ObjectStreamException#
         |java/io/OptionalDataException#
         |java/io/StreamCorruptedException#
         |java/io/SyncFailedException#
         |java/io/UTFDataFormatException#
         |java/io/UncheckedIOException#
         |java/io/UnsupportedEncodingException#
         |java/io/WriteAbortedException#
         |java/lang/ArithmeticException#
         |java/lang/ArrayIndexOutOfBoundsException#
         |java/lang/ArrayStoreException#
         |java/lang/ClassCastException#
         |java/lang/ClassNotFoundException#
         |java/lang/CloneNotSupportedException#
         |java/lang/EnumConstantNotPresentException#
         |java/lang/IllegalAccessException#
         |java/lang/IllegalArgumentException#
         |java/lang/IllegalCallerException#
         |java/lang/IllegalMonitorStateException#
         |java/lang/IllegalStateException#
         |java/lang/IllegalThreadStateException#
         |java/lang/IndexOutOfBoundsException#
         |java/lang/InstantiationException#
         |java/lang/InterruptedException#
         |java/lang/LayerInstantiationException#
         |java/lang/NegativeArraySizeException#
         |java/lang/NoSuchFieldException#
         |java/lang/NoSuchMethodException#
         |java/lang/NullPointerException#
         |java/lang/NumberFormatException#
         |java/lang/ReflectiveOperationException#
         |java/lang/RuntimeException#
         |java/lang/SecurityException#
         |java/lang/StringIndexOutOfBoundsException#
         |java/lang/TypeNotPresentException#
         |java/lang/UnsupportedOperationException#
         |java/lang/annotation/AnnotationTypeMismatchException#
         |java/lang/annotation/IncompleteAnnotationException#
         |java/lang/instrument/IllegalClassFormatException#
         |java/lang/instrument/UnmodifiableClassException#
         |java/lang/instrument/UnmodifiableModuleException#
         |java/lang/invoke/LambdaConversionException#
         |java/lang/invoke/StringConcatException#
         |java/lang/invoke/WrongMethodTypeException#
         |java/lang/module/FindException#
         |java/lang/module/InvalidModuleDescriptorException#
         |java/lang/module/ResolutionException#
         |java/lang/reflect/InaccessibleObjectException#
         |java/lang/reflect/InvocationTargetException#
         |java/lang/reflect/MalformedParameterizedTypeException#
         |java/lang/reflect/MalformedParametersException#
         |java/lang/reflect/UndeclaredThrowableException#
         |java/net/BindException#
         |java/net/ConnectException#
         |java/net/HttpRetryException#
         |java/net/MalformedURLException#
         |java/net/NoRouteToHostException#
         |java/net/PortUnreachableException#
         |java/net/ProtocolException#
         |java/net/SocketException#
         |java/net/SocketTimeoutException#
         |java/net/URISyntaxException#
         |java/net/UnknownHostException#
         |java/net/UnknownServiceException#
         |java/net/http/HttpConnectTimeoutException#
         |java/net/http/HttpTimeoutException#
         |java/net/http/WebSocketHandshakeException#
         |java/nio/BufferOverflowException#
         |java/nio/BufferUnderflowException#
         |java/nio/InvalidMarkException#
         |java/nio/ReadOnlyBufferException#
         |java/nio/channels/AcceptPendingException#
         |java/nio/channels/AlreadyBoundException#
         |java/nio/channels/AlreadyConnectedException#
         |java/nio/channels/AsynchronousCloseException#
         |java/nio/channels/CancelledKeyException#
         |java/nio/channels/ClosedByInterruptException#
         |java/nio/channels/ClosedChannelException#
         |java/nio/channels/ClosedSelectorException#
         |java/nio/channels/ConnectionPendingException#
         |java/nio/channels/FileLockInterruptionException#
         |java/nio/channels/IllegalBlockingModeException#
         |java/nio/channels/IllegalChannelGroupException#
         |java/nio/channels/IllegalSelectorException#
         |java/nio/channels/InterruptedByTimeoutException#
         |java/nio/channels/NoConnectionPendingException#
         |java/nio/channels/NonReadableChannelException#
         |java/nio/channels/NonWritableChannelException#
         |java/nio/channels/NotYetBoundException#
         |java/nio/channels/NotYetConnectedException#
         |java/nio/channels/OverlappingFileLockException#
         |java/nio/channels/ReadPendingException#
         |java/nio/channels/ShutdownChannelGroupException#
         |java/nio/channels/UnresolvedAddressException#
         |java/nio/channels/UnsupportedAddressTypeException#
         |java/nio/channels/WritePendingException#
         |java/nio/charset/CharacterCodingException#
         |java/nio/charset/IllegalCharsetNameException#
         |java/nio/charset/MalformedInputException#
         |java/nio/charset/UnmappableCharacterException#
         |java/nio/charset/UnsupportedCharsetException#
         |java/nio/file/AccessDeniedException#
         |java/nio/file/AtomicMoveNotSupportedException#
         |java/nio/file/ClosedDirectoryStreamException#
         |java/nio/file/ClosedFileSystemException#
         |java/nio/file/ClosedWatchServiceException#
         |java/nio/file/DirectoryIteratorException#
         |java/nio/file/DirectoryNotEmptyException#
         |java/nio/file/FileAlreadyExistsException#
         |java/nio/file/FileSystemAlreadyExistsException#
         |java/nio/file/FileSystemException#
         |java/nio/file/FileSystemLoopException#
         |java/nio/file/FileSystemNotFoundException#
         |java/nio/file/InvalidPathException#
         |java/nio/file/NoSuchFileException#
         |java/nio/file/NotDirectoryException#
         |java/nio/file/NotLinkException#
         |java/nio/file/ProviderMismatchException#
         |java/nio/file/ProviderNotFoundException#
         |java/nio/file/ReadOnlyFileSystemException#
         |java/nio/file/attribute/UserPrincipalNotFoundException#
         |java/rmi/AccessException#
         |java/rmi/AlreadyBoundException#
         |java/rmi/ConnectException#
         |java/rmi/ConnectIOException#
         |java/rmi/MarshalException#
         |java/rmi/NoSuchObjectException#
         |java/rmi/NotBoundException#
         |java/rmi/RMISecurityException#
         |java/rmi/RemoteException#
         |java/rmi/ServerError#
         |java/rmi/ServerException#
         |java/rmi/ServerRuntimeException#
         |java/rmi/StubNotFoundException#
         |java/rmi/UnexpectedException#
         |java/rmi/UnknownHostException#
         |java/rmi/UnmarshalException#
         |java/rmi/server/ExportException#
         |java/rmi/server/ServerCloneException#
         |java/rmi/server/ServerNotActiveException#
         |java/rmi/server/SkeletonMismatchException#
         |java/rmi/server/SkeletonNotFoundException#
         |java/rmi/server/SocketSecurityException#
         |java/security/AccessControlException#
         |java/security/DigestException#
         |java/security/GeneralSecurityException#
         |java/security/InvalidAlgorithmParameterException#
         |java/security/InvalidKeyException#
         |java/security/InvalidParameterException#
         |java/security/KeyException#
         |java/security/KeyManagementException#
         |java/security/KeyStoreException#
         |java/security/NoSuchAlgorithmException#
         |java/security/NoSuchProviderException#
         |java/security/PrivilegedActionException#
         |java/security/ProviderException#
         |java/security/SignatureException#
         |java/security/UnrecoverableEntryException#
         |java/security/UnrecoverableKeyException#
         |java/security/cert/CRLException#
         |java/security/cert/CertPathBuilderException#
         |java/security/cert/CertPathValidatorException#
         |java/security/cert/CertStoreException#
         |java/security/cert/CertificateEncodingException#
         |java/security/cert/CertificateException#
         |java/security/cert/CertificateExpiredException#
         |java/security/cert/CertificateNotYetValidException#
         |java/security/cert/CertificateParsingException#
         |java/security/cert/CertificateRevokedException#
         |java/security/spec/InvalidKeySpecException#
         |java/security/spec/InvalidParameterSpecException#
         |java/sql/BatchUpdateException#
         |java/sql/DataTruncation#
         |java/sql/SQLClientInfoException#
         |java/sql/SQLDataException#
         |java/sql/SQLException#
         |java/sql/SQLFeatureNotSupportedException#
         |java/sql/SQLIntegrityConstraintViolationException#
         |java/sql/SQLInvalidAuthorizationSpecException#
         |java/sql/SQLNonTransientConnectionException#
         |java/sql/SQLNonTransientException#
         |java/sql/SQLRecoverableException#
         |java/sql/SQLSyntaxErrorException#
         |java/sql/SQLTimeoutException#
         |java/sql/SQLTransactionRollbackException#
         |java/sql/SQLTransientConnectionException#
         |java/sql/SQLTransientException#
         |java/sql/SQLWarning#
         |java/text/ParseException#
         |java/time/DateTimeException#
         |java/time/format/DateTimeParseException#
         |java/time/temporal/UnsupportedTemporalTypeException#
         |java/time/zone/ZoneRulesException#
         |java/util/ConcurrentModificationException#
         |java/util/DuplicateFormatFlagsException#
         |java/util/EmptyStackException#
         |java/util/FormatFlagsConversionMismatchException#
         |java/util/FormatterClosedException#
         |java/util/IllegalFormatArgumentIndexException#
         |java/util/IllegalFormatCodePointException#
         |java/util/IllegalFormatConversionException#
         |java/util/IllegalFormatException#
         |java/util/IllegalFormatFlagsException#
         |java/util/IllegalFormatPrecisionException#
         |java/util/IllegalFormatWidthException#
         |java/util/IllformedLocaleException#
         |java/util/InputMismatchException#
         |java/util/InvalidPropertiesFormatException#
         |java/util/MissingFormatArgumentException#
         |java/util/MissingFormatWidthException#
         |java/util/MissingResourceException#
         |java/util/NoSuchElementException#
         |java/util/TooManyListenersException#
         |java/util/UnknownFormatConversionException#
         |java/util/UnknownFormatFlagsException#
         |java/util/concurrent/BrokenBarrierException#
         |java/util/concurrent/CancellationException#
         |java/util/concurrent/CompletionException#
         |java/util/concurrent/ExecutionException#
         |java/util/concurrent/RejectedExecutionException#
         |java/util/concurrent/TimeoutException#
         |java/util/jar/JarException#
         |java/util/prefs/BackingStoreException#
         |java/util/prefs/InvalidPreferencesFormatException#
         |java/util/regex/PatternSyntaxException#
         |java/util/zip/DataFormatException#
         |java/util/zip/ZipException#
         |javax/annotation/processing/FilerException#
         |javax/crypto/AEADBadTagException#
         |javax/crypto/BadPaddingException#
         |javax/crypto/ExemptionMechanismException#
         |javax/crypto/IllegalBlockSizeException#
         |javax/crypto/NoSuchPaddingException#
         |javax/crypto/ShortBufferException#
         |javax/imageio/IIOException#
         |javax/imageio/metadata/IIODOMException#
         |javax/imageio/metadata/IIOInvalidTreeException#
         |javax/lang/model/UnknownEntityException#
         |javax/lang/model/element/UnknownAnnotationValueException#
         |javax/lang/model/element/UnknownDirectiveException#
         |javax/lang/model/element/UnknownElementException#
         |javax/lang/model/type/MirroredTypeException#
         |javax/lang/model/type/MirroredTypesException#
         |javax/lang/model/type/UnknownTypeException#
         |javax/management/AttributeNotFoundException#
         |javax/management/BadAttributeValueExpException#
         |javax/management/BadBinaryOpValueExpException#
         |javax/management/BadStringOperationException#
         |javax/management/InstanceAlreadyExistsException#
         |javax/management/InstanceNotFoundException#
         |javax/management/IntrospectionException#
         |javax/management/InvalidApplicationException#
         |javax/management/InvalidAttributeValueException#
         |javax/management/JMException#
         |javax/management/JMRuntimeException#
         |javax/management/ListenerNotFoundException#
         |javax/management/MBeanException#
         |javax/management/MBeanRegistrationException#
         |javax/management/MalformedObjectNameException#
         |javax/management/NotCompliantMBeanException#
         |javax/management/OperationsException#
         |javax/management/ReflectionException#
         |javax/management/RuntimeErrorException#
         |javax/management/RuntimeMBeanException#
         |javax/management/RuntimeOperationsException#
         |javax/management/ServiceNotFoundException#
         |javax/management/modelmbean/InvalidTargetObjectTypeException#
         |javax/management/modelmbean/XMLParseException#
         |javax/management/monitor/MonitorSettingException#
         |javax/management/openmbean/InvalidKeyException#
         |javax/management/openmbean/InvalidOpenTypeException#
         |javax/management/openmbean/KeyAlreadyExistsException#
         |javax/management/openmbean/OpenDataException#
         |javax/management/relation/InvalidRelationIdException#
         |javax/management/relation/InvalidRelationServiceException#
         |javax/management/relation/InvalidRelationTypeException#
         |javax/management/relation/InvalidRoleInfoException#
         |javax/management/relation/InvalidRoleValueException#
         |javax/management/relation/RelationException#
         |javax/management/relation/RelationNotFoundException#
         |javax/management/relation/RelationServiceNotRegisteredException#
         |javax/management/relation/RelationTypeNotFoundException#
         |javax/management/relation/RoleInfoNotFoundException#
         |javax/management/relation/RoleNotFoundException#
         |javax/management/remote/JMXProviderException#
         |javax/management/remote/JMXServerErrorException#
         |javax/naming/AuthenticationException#
         |javax/naming/AuthenticationNotSupportedException#
         |javax/naming/CannotProceedException#
         |javax/naming/CommunicationException#
         |javax/naming/ConfigurationException#
         |javax/naming/ContextNotEmptyException#
         |javax/naming/InsufficientResourcesException#
         |javax/naming/InterruptedNamingException#
         |javax/naming/InvalidNameException#
         |javax/naming/LimitExceededException#
         |javax/naming/LinkException#
         |javax/naming/LinkLoopException#
         |javax/naming/MalformedLinkException#
         |javax/naming/NameAlreadyBoundException#
         |javax/naming/NameNotFoundException#
         |javax/naming/NamingException#
         |javax/naming/NamingSecurityException#
         |javax/naming/NoInitialContextException#
         |javax/naming/NoPermissionException#
         |javax/naming/NotContextException#
         |javax/naming/OperationNotSupportedException#
         |javax/naming/PartialResultException#
         |javax/naming/ReferralException#
         |javax/naming/ServiceUnavailableException#
         |javax/naming/SizeLimitExceededException#
         |javax/naming/TimeLimitExceededException#
         |javax/naming/directory/AttributeInUseException#
         |javax/naming/directory/AttributeModificationException#
         |javax/naming/directory/InvalidAttributeIdentifierException#
         |javax/naming/directory/InvalidAttributeValueException#
         |javax/naming/directory/InvalidAttributesException#
         |javax/naming/directory/InvalidSearchControlsException#
         |javax/naming/directory/InvalidSearchFilterException#
         |javax/naming/directory/NoSuchAttributeException#
         |javax/naming/directory/SchemaViolationException#
         |javax/naming/ldap/LdapReferralException#
         |javax/net/ssl/SSLException#
         |javax/net/ssl/SSLHandshakeException#
         |javax/net/ssl/SSLKeyException#
         |javax/net/ssl/SSLPeerUnverifiedException#
         |javax/net/ssl/SSLProtocolException#
         |javax/print/PrintException#
         |javax/print/attribute/UnmodifiableSetException#
         |javax/script/ScriptException#
         |javax/security/auth/DestroyFailedException#
         |javax/security/auth/RefreshFailedException#
         |javax/security/auth/callback/UnsupportedCallbackException#
         |javax/security/auth/login/AccountException#
         |javax/security/auth/login/AccountExpiredException#
         |javax/security/auth/login/AccountLockedException#
         |javax/security/auth/login/AccountNotFoundException#
         |javax/security/auth/login/CredentialException#
         |javax/security/auth/login/CredentialExpiredException#
         |javax/security/auth/login/CredentialNotFoundException#
         |javax/security/auth/login/FailedLoginException#
         |javax/security/auth/login/LoginException#
         |javax/security/cert/CertificateEncodingException#
         |javax/security/cert/CertificateException#
         |javax/security/cert/CertificateExpiredException#
         |javax/security/cert/CertificateNotYetValidException#
         |javax/security/cert/CertificateParsingException#
         |javax/security/sasl/AuthenticationException#
         |javax/security/sasl/SaslException#
         |javax/smartcardio/CardException#
         |javax/smartcardio/CardNotPresentException#
         |javax/sound/midi/InvalidMidiDataException#
         |javax/sound/midi/MidiUnavailableException#
         |javax/sound/sampled/LineUnavailableException#
         |javax/sound/sampled/UnsupportedAudioFileException#
         |javax/sql/rowset/RowSetWarning#
         |javax/sql/rowset/serial/SerialException#
         |javax/sql/rowset/spi/SyncFactoryException#
         |javax/sql/rowset/spi/SyncProviderException#
         |javax/swing/UnsupportedLookAndFeelException#
         |javax/swing/text/BadLocationException#
         |javax/swing/text/ChangedCharSetException#
         |javax/swing/tree/ExpandVetoException#
         |javax/swing/undo/CannotRedoException#
         |javax/swing/undo/CannotUndoException#
         |""".stripMargin,
      topLines = Some(500),
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

  override protected def libraryDependencies: List[String] =
    List("org.scalatest::scalatest:3.2.16", "io.circe::circe-generic:0.12.0")

}
