package tests.pc

import tests.BasePcRenameSuite

class PcPrepareRenameSuite extends BasePcRenameSuite {

  prepare(
    "prepare-import",
    """|package a
       |
       |import java.util.{List => `J-List`}
       |
       |object Main{
       |  def m() = {
       |    val toRename: `J-L@@ist`[Int] = ???
       |    val toRename2: `J-List`[Int] = ???
       |    val toRename3: java.util.List[Int] = ???
       |  }
       |}
       |""".stripMargin,
  )

  // currently we are not using presentation compiler in this case
  prepare(
    "prepare-import-object",
    """|package a
       |
       |object Renaming {
       |  def m() = {
       |    import scala.util.{Try => StdLibTry}  
       |    def foo(n: Int): StdLib@@Try[Int] = 
       |      StdLibTryprepare(n)
       |  }
       |}
       |""".stripMargin,
  )

  prepare(
    "case",
    """|package a
       |case class Userprepare(name : String)
       |object Main{
       |  def m() = {
       |    val user = User.apply("James")
       |    val user2 = U@@serprepare(name = "Roger")
       |    user.copy(name = "")
       |  }
       |}
       |""".stripMargin,
  )

  prepare(
    "generics",
    """|package a
       |trait S1[X] { def torename(p: X): String = "" }
       |trait T1[Z] extends S1[Z] { override def tore@@name(p: Z): String = super.torename(p) }
       |""".stripMargin,
  )

  prepare(
    "match-ret-type",
    """|package a
       |trait P
       |trait PP extends P
       |trait A { def torename(a: String): P = ??? }
       |trait B extends A { override def tore@@name(a: String): PP = ??? }
       |
       |""".stripMargin,
  )

  prepare(
    "unapply",
    """|object F@@oo {
       |  def unapply(s: String): Option[String] = Some("")
       |}
       |
       |object Main{
       |  def m() = {
       |    "foo" match {
       |      case Fooprepare(s) => ()
       |    }
       |  }
       |}
       |""".stripMargin,
  )

  prepare(
    "unapply-param",
    """|object Foo {
       |  def unapply(<<nam@@e>>: String): Option[String] = Some(name)
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
       |    val <<toRen@@ame>> = 123
       |    toRename
       |  }
       |}
       |""".stripMargin,
  )

  prepare(
    "method",
    """|package a
       |object Main{
       |  def m() = {
       |    def <<met@@hodprepare>>(abc : String) = true
       |    if(methodprepare("")) println("Is true!")
       |  }
       |}
       |""".stripMargin,
  )

  prepare(
    "self-type",
    """|package a
       |
       |object Main{
       |  def m() = {
       |    trait <<A@@BC>>
       |    trait Alphabet{
       |      this: ABC =>
       |    }
       |    val a = new Alphabet with ABC
       |  }
       |}
       |""".stripMargin,
  )

  prepare(
    "method-inheritance",
    """|package a
       |trait Hello{
       |  def method(abc : String) : Boolean
       |}
       |
       |class GoodMorning extends Hello {
       |  def met@@hod(abc : String) = true
       |}
       |""".stripMargin,
  )

  prepare(
    "apply",
    """|package a
       |object User{
       |  def ap@@ply(name : String) = name
       |  def apply(name : String, age: Int) = name
       |}
       |object Main{
       |  val toRename = User##.##("abc")
       |}
       |""".stripMargin,
  )

  prepare(
    "colon-bad",
    """|package a  
       |object Main{
       |  def m() = {
       |    class User{
       |      def <<:@@:>>(name : String) = name
       |    }
       |    val user = new User()
       |    "" :: user
       |  }
       |}
       |""".stripMargin,
  )

  prepare(
    "unary-bad",
    """|package a
       |
       |object Main{
       |  def m() = {
       |    class User{
       |      def unary_! = false
       |    }
       |    val user = new User()
       |    @@!user
       |  }
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
    "companion",
    """|package a
       |class Main{}
       |object M@@ain
       |""".stripMargin,
  )

  prepare(
    "companion2",
    """|package a
       |object a {
       |  def m() = {
       |    class <<Ma@@in>>{}
       |    object Main
       |
       |  }
       |}
       |""".stripMargin,
  )

  prepare(
    "anon",
    """|trait Methodable[T] {
       |  def methodprepare(asf: T): Int
       |}
       |
       |trait Alphabet extends Methodable[String] {
       |  def methodprepare(adf: String) = 123
       |}
       |
       |object Main {
       |  val a = new Alphabet {
       |    override def me@@thod(adf: String): Int = 321
       |  }
       |}
       |""".stripMargin,
  )

  prepare(
    "anon2",
    """|object a {
       |  def m() = {
       |    trait Methodable[T] {
       |      def methodprepare(asf: T): Int
       |    }
       |
       |    trait Alphabet extends Methodable[String] {
       |      def methodprepare(adf: String) = 123
       |    }
       |
       |    object Main {
       |      val a = new Alphabet {
       |        override def <<me@@thod>>(adf: String): Int = 321
       |    }
       |  }
       |}
       |""".stripMargin,
  )

  prepare(
    "macro",
    """|package a
       |import io.circe.generic.JsonCodec
       |trait LivingBeing
       |object Main {
       |  def m() = {
       |    @JsonCodec sealed trait <<An@@imal>> extends LivingBeing
       |    object Animal {
       |      case object Dog extends Animal
       |      case object Cat extends Animal
       |    }
       |  }
       |}
       |""".stripMargin,
  )

  prepare(
    "macro2",
    """|package a
       |import io.circe.generic.JsonCodec
       |@JsonCodec
       |final case class Ma@@in2(name: String)
       |""".stripMargin,
  )

  prepare(
    "implicit-param",
    """|package a
       |object A {
       |  implicit val some@@Name: Int = 1
       |  def m[A](implicit a: A): A = a
       |  m[Int]
       |}""".stripMargin,
  )

  prepare(
    "backtick2",
    """|package a
       |object Main{
       |  val greeting = "Hello"
       |  "" match {
       |    case `gre@@eting` =>
       |  }
       |}
       |""".stripMargin,
  )

  prepare(
    "backtick3",
    """|package a
       |object Main{
       |  def local = {
       |    val <<greet@@ing>> = "Hello"
       |    "" match {
       |      case `greeting` =>
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
    """|
       |
       |object Main {
       |  def m() = {
       |    case class Name(<<va@@lue>>: String)
       |    val name1 = Name(value = "42")
       |      .copy(value = "43")
       |      .copy(value = "43")
       |      .value
       |    val name2 = Name(value = "44")
       |  }
       |}
       |""".stripMargin,
  )

  prepare(
    "constructor",
    """|case class Name(va@@lue: String)
       |
       |object Main {
       |  val name2 = new Name(value = "44")
       |}
       |""".stripMargin,
  )

  prepare(
    "type-params",
    """|package a
       |trait ABC
       |class CBD[T <: AB@@C]
       |object Main{
       |  val a = classOf[ABC]
       |  val b = new CBD[ABC]
       |}
       |""".stripMargin,
  )

  prepare(
    "hierarchy-inside-method-trait",
    """|package a
       |object Main {
       |  final def main(args: Array[String]) = {
       |    sealed trait <<Sy@@mbol>>
       |    case class Method(name: String) extends Symbol
       |    case class Variable(value: String) extends Symbol
       |
       |    val symbol2: Symbol = Method("method")
       |    val symbol3: Symbol = Variable("value")
       |  }
       |}
       |""".stripMargin,
  )

  prepare(
    "hierarchy-inside-method-class",
    """|package a
       |object Main {
       |  final def main(args: Array[String]) = {
       |    sealed abstract class <<Sy@@mbol>>
       |    case class Method(name: String) extends Symbol
       |    case class Variable(value: String) extends Symbol
       |
       |    val symbol2: Symbol = Method("method")
       |    val symbol3: Symbol = Variable("value")
       |  }
       |}
       |""".stripMargin,
  )

  prepare(
    "variable",
    """|package a
       |object Main {
       |  var v@@5 = false
       |
       |  def f5: Boolean = {
       |    v5 = true
       |    v5 == true
       |  }
       |}
       |""".stripMargin,
  )

  prepare(
    "variable-explicit1",
    """|package a
       |object Main {
       |  var v@@5 = false
       |
       |  def f5: Boolean = {
       |    v5_=(true)
       |    v5 == true
       |  }
       |}
       |""".stripMargin,
  )

  prepare(
    "worksheet-method",
    """|trait S1[X] { def torename(p: X): String = "" }
       |trait T1[Z] extends S1[Z] { override def <<tore@@name>>(p: Z): String = super.torename(p) }
       |""".stripMargin,
    filename = "A.worksheet.sc",
  )
  prepare(
    "worksheet-classes",
    """|sealed abstract class <<Sy@@mbol>>
       |case class Method(name: String) extends Symbol
       |case class Variable(value: String) extends Symbol
       |""".stripMargin,
    filename = "A.worksheet.sc",
  )

  prepare(
    "not-compiling",
    """|package a
       |object Main {
       |  def method() = {
       |    List(1) + 2
       |    val abc: Option[Int] = ???
       |    <<ab@@c>>.map(_ + 1)
       |  }
       |}
       |""".stripMargin,
  )

  prepare(
    "extension-param".tag(IgnoreScala2),
    """|extension (<<sb@@d>>: String)
       |  def double = sbd + sbd
       |  def double2 = sbd + sbd
       |end extension
       |""".stripMargin,
  )

  prepare(
    "extension-params-ref".tag(IgnoreScala2),
    """|extension (sbd: String)
       |  def double = <<sb@@d>> + sbd
       |  def double2 = sbd + sbd
       |end extension
       |""".stripMargin,
  )

  prepare(
    "extension-type-param".tag(IgnoreScala2),
    """|extension [T](<<x@@s>>: List[T])
       |  def double = xs ++ xs
       |  def double2 = xs ++ xs
       |end extension
       |""".stripMargin,
  )

  prepare(
    "extension-type-param-ref".tag(IgnoreScala2),
    """|extension [T](xs: List[T])
       |  def double = xs ++ xs
       |  def double2 = xs ++ <<x@@s>>
       |end extension
       |""".stripMargin,
  )

}
