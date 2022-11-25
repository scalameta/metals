package tests.pc

import tests.BasePcRenameSuite

class PcPrepareRenameSuite extends BasePcRenameSuite {

  prepare(
    "basic",
    """package a
      |object Main2{
      |  val toRename = Main.preparetoR@@enameprepare
      |}
      |""".stripMargin,
  )

  prepare(
    "prepare-import",
    """|package a
       |
       |import java.util.{List => `J-List`}
       |
       |object Main{
       |  val toRename: prepare`J-L@@ist`prepare[Int] = ???
       |  val toRename2: `J-List`[Int] = ???
       |  val toRename3: java.util.List[Int] = ???
       |}
       |""".stripMargin,
  )

  prepare(
    "prepare-import-local",
    """|package a
       |
       |import a.{Main2 => prepareOt@@herMainprepare}
       |
       |object Main{
       |  val toRename = OtherMain.toRename
       |}
       |""".stripMargin,
  )

  prepare(
    "prepare-import-object",
    """|package a
       |
       |import scala.util.{Try => StdLibTry}
       |
       |object Renaming {
       |  def foo(n: Int): prepareStdLib@@Tryprepare[Int] = 
       |    prepareStdLibTryprepare(n)
       |}
       |""".stripMargin,
  )

  prepare(
    "case",
    """|package a
       |case class prepareUserprepare(name : String)
       |object Main{
       |  val user = prepareUserprepare.apply("James")
       |  val user2 = prepareU@@serprepare(name = "Roger")
       |  user.copy(name = "")
       |}
       |""".stripMargin,
  )

  prepare(
    "generics",
    """|package a
       |trait S1[X] { def preparetorenameprepare(p: X): String = "" }
       |trait T1[Z] extends S1[Z] { override def preparetorenameprepare(p: Z): String = super.preparetorenameprepare(p) }
       |trait T2[X] extends T1[X] { override def preparetorenameprepare(p: X): String = super.preparetorenameprepare(p) }
       |trait T3[I, J] extends T2[I] { override def preparetorenameprepare(p: I): String = super.preparetorenameprepare(p) }
       |trait T4[I, J] extends T3[J, I] { override def preparetorenameprepare(p: J): String = super.preparetorenameprepare(p) }
       |trait T5[U] extends T4[U, U] { override def preparetore@@nameprepare(p: U): String = super.preparetorenameprepare(p) }
       |""".stripMargin,
  )

  prepare(
    "match-ret-type",
    """|package a
       |trait P
       |trait PP extends P
       |trait A { def preparetorenameprepare(a: String): P = ??? }
       |trait B extends A { override def preparetore@@nameprepare(a: String): PP = ??? }
       |
       |""".stripMargin,
  )

  prepare(
    "across-targets",
    """|package a
       |object Main{
       |  val preparetoRenameprepare = 123
       |}
       |/b/src/main/scala/b/Main2.scala
       |package b
       |import a.Main
       |object Main2{
       |  val toRename = Main.preparetoR@@enameprepare
       |}
       |""".stripMargin,
  )

  prepare(
    "unapply",
    """|object prepareF@@ooprepare {
       |  def unapply(s: String): Option[String] = Some("")
       |}
       |
       |object Main{
       |  "foo" match {
       |    case prepareFooprepare(s) => ()
       |  }
       |}
       |""".stripMargin,
  )

  prepare(
    "unapply-param",
    """|object Foo {
       |  def unapply(<<preparenam@@eprepare>>: String): Option[String] = Some(preparenameprepare)
       |}
       |
       |object Main{
       |  "foo" match {
       |    case Foo(name) => ()
       |  }
       |}
       |""".stripMargin,
  )

  prepare(
    "local",
    """|package a
       |object Main{
       |  def hello() = {
       |    val <<preparetoRen@@ameprepare>> = 123
       |    preparetoRenameprepare
       |  }
       |}
       |""".stripMargin,
  )

  prepare(
    "method",
    """|package a
       |object Main{
       |  def preparemet@@hodprepare(abc : String) = true
       |
       |  if(preparemethodprepare("")) println("Is true!")
       |}
       |""".stripMargin,
  )

  prepare(
    "self-type",
    """|package a
       |trait prepareA@@BCprepare
       |trait Alphabet{
       |  this: prepareABCprepare =>
       |}
       |object Main{
       |  val a = new Alphabet with prepareABCprepare
       |}
       |""".stripMargin,
  )

  prepare(
    "method-inheritance",
    """|package a
       |trait Hello{
       |  def preparemethodprepare(abc : String) : Boolean
       |}
       |
       |class GoodMorning extends Hello {
       |  def preparemet@@hodprepare(abc : String) = true
       |}
       |""".stripMargin,
  )

  prepare(
    "long-inheritance",
    """|package a
       |trait A[T, S] {
       |  def preparemethodprepare(abc : T) : S
       |}
       |
       |abstract class B[T] extends A[T, Boolean] {
       |  def preparemethodprepare(abc : T) : Boolean
       |}
       |
       |abstract class C extends B[String] {
       |  def preparemeth@@odprepare(abc : String) : Boolean = false
       |}
       |""".stripMargin,
  )

  prepare(
    "multiple-inheritance",
    """|package a
       |trait A {
       |  def preparemethodprepare(abc : String) : Boolean
       |}
       |
       |trait B {
       |  def preparemethodprepare(abc : String) : Boolean = true
       |}
       |
       |abstract class C extends B with A {
       |  override def preparemeth@@odprepare(abc : String) : Boolean = false
       |}
       |""".stripMargin,
  )

  prepare(
    "apply",
    """|package a
       |object User{
       |  def prepareap@@plyprepare(name : String) = name
       |  def apply(name : String, age: Int) = name
       |}
       |object Main{
       |  val toRename = User##.##prepareprepare("abc")
       |}
       |""".stripMargin,
  )

  prepare(
    "colon-bad",
    """|package a
       |class User{
       |  def prepare:@@:prepare(name : String) = name
       |}
       |object Main{
       |  val user = new User()
       |  "" prepare::prepare user
       |}
       |""".stripMargin,
  )

  prepare(
    "colon-good",
    """|package a
       |class User{
       |  def prepare:@@:prepare(name : String) = name
       |}
       |object Main{
       |  val user = new User()
       |  "" prepare::prepare user
       |}
       |""".stripMargin,
  )

  prepare(
    "unary-bad",
    """|package a
       |class User{
       |  def prepareunary_!prepare = false
       |}
       |object Main{
       |  val user = new User()
       |  prepare@@!prepareuser
       |}
       |""".stripMargin,
  )

  prepare(
    "unary-bad2",
    """|package a
       |class User{
       |  def prepareu@@nary_!prepare = false
       |}
       |object Main{
       |  val user = new User()
       |  prepare!prepareuser
       |}
       |""".stripMargin,
  )

  prepare(
    "java-classes",
    """|package a
       |class MyException extends Exce@@ption
       |class NewException extends RuntimeException
       |class NewException2 extends RuntimeException
       |""".stripMargin,
  )

  prepare(
    "inheritance",
    """|package a
       |abstract class prepareAn@@imalprepare
       |class Dog extends prepareAnimalprepare
       |class Cat extends prepareAnimalprepare
       |""".stripMargin,
  )

  prepare(
    "companion",
    """|package a
       |class prepareMainprepare{}
       |object prepareM@@ainprepare
       |""".stripMargin,
  )

  prepare(
    "companion2",
    """|package a
       |class prepareMa@@inprepare{}
       |object prepareMainprepare
       |""".stripMargin,
  )

  prepare(
    "filename-exact-match",
    """|package a
       |object prepareMa@@inprepare
       |object TheMain
       |""".stripMargin,
  )

  prepare(
    "filename-exact-match-2",
    """|package a
       |object Main
       |object prepareThe@@Mainprepare
       |""".stripMargin,
  )


  prepare(
    "anon",
    """|trait Methodable[T] {
       |  def preparemethodprepare(asf: T): Int
       |}
       |
       |trait Alphabet extends Methodable[String] {
       |  def preparemethodprepare(adf: String) = 123
       |}
       |
       |object Main {
       |  val a = new Alphabet {
       |    override def <<prepareme@@thodprepare>>(adf: String): Int = 321
       |  }
       |}
       |""".stripMargin,
  )



  prepare(
    "macro",
    """|package a
       |import io.circe.generic.JsonCodec
       |trait LivingBeing
       |@JsonCodec sealed trait prepareAn@@imalprepare extends LivingBeing
       |object prepareAnimalprepare {
       |  case object Dog extends prepareAnimalprepare
       |  case object Cat extends prepareAnimalprepare
       |}
       |""".stripMargin,
  )

  prepare(
    "macro2",
    """|package a
       |import io.circe.generic.JsonCodec
       |@JsonCodec
       |final case class prepareMa@@in2prepare(name: String)
       |""".stripMargin,
  )



  prepare(
    "implicit-param",
    """|package a
       |object A {
       |  implicit val preparesome@@Nameprepare: Int = 1
       |  def m[A](implicit a: A): A = a
       |  m[Int]
       |}""".stripMargin,
  )

  prepare(
    "nested-symbol",
    """|package a
       |object Foo {
       |  object prepareMa@@inprepare
       |}
       |""".stripMargin,
  )


  prepare(
    "backtick-old-and-new-name",
    """|package a
       |object Main{
       |  val prepare`to-Rename`prepare = 123
       |}
       |object Main2{
       |  val toRename = Main.prepare`to-R@@ename`prepare
       |}
       |""".stripMargin,
  )

  prepare(
    "backtick",
    """|package a
       |object Main{
       |  val preparegreet@@ingprepare = "Hello"
       |  "" match {
       |    case `preparegreetingprepare` =>
       |  }
       |}
       |""".stripMargin,
  )

  prepare(
    "double-backtick",
    """|package a
       |object Main{
       |  val preparegreet@@ingprepare = "Hello"
       |  "" match {
       |    case prepare`greeting`prepare =>
       |  }
       |}
       |""".stripMargin,
  )

  prepare(
    "backtick2",
    """|package a
       |object Main{
       |  val preparegreetingprepare = "Hello"
       |  "" match {
       |    case `preparegre@@etingprepare` =>
       |  }
       |}
       |""".stripMargin,
  )

  prepare(
    "backtick3",
    """|package a
       |object Main{
       |  def local = {
       |    val <<preparegreet@@ingprepare>> = "Hello"
       |    "" match {
       |      case `preparegreetingprepare` =>
       |    }
       |  }
       |}
       |""".stripMargin,
  )

  // If renaming in VS Code, backticks are taken as part of the name
  prepare(
    "backtick4",
    """|package a
       |object Main{
       |  def local = {
       |    val greeting = "Hello"
       |    "" match {
       |      case <<`gre@@eting`>> =>
       |    }
       |  }
       |}
       |""".stripMargin,
  )

  prepare(
    "params",
    """|case class Name(prepareva@@lueprepare: String)
       |
       |object Main {
       |  val name1 = Name(preparevalueprepare = "42")
       |   .copy(preparevalueprepare = "43")
       |   .copy(preparevalueprepare = "43")
       |   .preparevalueprepare
       |  val name2 = Name(preparevalueprepare = "44")
       |}
       |""".stripMargin,
  )

  prepare(
    "constructor",
    """|case class Name(prepareva@@lueprepare: String)
       |
       |object Main {
       |  val name2 = new Name(preparevalueprepare = "44")
       |}
       |""".stripMargin,
  )

  prepare(
    "type-params",
    """|package a
       |trait prepareABCprepare
       |class CBD[T <: prepareAB@@Cprepare]
       |object Main{
       |  val a = classOf[prepareABCprepare]
       |  val b = new CBD[prepareABCprepare]
       |}
       |""".stripMargin,
  )

  prepare(
    "implicit-parameter",
    """|trait A {
       | implicit def preparefooprepare: Double
       |}
       |object A extends A {
       |  implicit def preparefo@@oprepare: Double = 0.1
       |  def bar(implicit x: Double): Double = x
       |  val x = bar
       |}
       |""".stripMargin,
  )


  prepare(
    "hierarchy-inside-method-trait",
    """|package a
       |object Main {
       |  final def main(args: Array[String]) = {
       |    sealed trait <<prepareSy@@mbolprepare>>
       |    case class Method(name: String) extends prepareSymbolprepare
       |    case class Variable(value: String) extends prepareSymbolprepare
       |
       |    val symbol2: prepareSymbolprepare = Method("method")
       |    val symbol3: prepareSymbolprepare = Variable("value")
       |  }
       |}
       |""".stripMargin,
  )

  prepare(
    "hierarchy-inside-method-class",
    """|package a
       |object Main {
       |  final def main(args: Array[String]) = {
       |    sealed abstract class <<prepareSy@@mbolprepare>>
       |    case class Method(name: String) extends prepareSymbolprepare
       |    case class Variable(value: String) extends prepareSymbolprepare
       |
       |    val symbol2: prepareSymbolprepare = Method("method")
       |    val symbol3: prepareSymbolprepare = Variable("value")
       |  }
       |}
       |""".stripMargin,
  )

  prepare(
    "variable",
    """|package a
       |object Main {
       |  var preparev@@5prepare = false
       |
       |  def f5: Boolean = {
       |    preparev5prepare = true
       |    preparev5prepare == true
       |  }
       |}
       |""".stripMargin,
  )

  prepare(
    "variable-explicit1",
    """|package a
       |object Main {
       |  var preparev@@5prepare = false
       |
       |  def f5: Boolean = {
       |    preparev5prepare_=(true)
       |    preparev5prepare == true
       |  }
       |}
       |""".stripMargin,
  )

  prepare(
    "variable-explicit2",
    """|package a
       |object Main {
       |  var preparev5prepare = false
       |
       |  def f5: Boolean = {
       |    <<`preparev@@5prepare_=`>>(true)
       |    preparev5prepare == true
       |  }
       |}
       |""".stripMargin,
  )

  // NON COMPILING TESTS

  prepare(
    "not-compiling",
    """|package a
       |object Main {
       |  def method() = {
       |    List(1) + 2
       |    val prepareabcprepare: Option[Int] = ???
       |    <<prepareab@@cprepare>>.map(_ + 1)
       |  }
       |}
       |""".stripMargin,
  )

}
