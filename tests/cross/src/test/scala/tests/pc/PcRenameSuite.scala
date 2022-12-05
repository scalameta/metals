package tests.pc

import coursierapi.Dependency
import tests.BasePcRenameSuite

class PcRenameSuite extends BasePcRenameSuite {
  override def extraDependencies(scalaVersion: String): Seq[Dependency] = {
    val scalaBinaryVersion = createBinaryVersion(scalaVersion)
    Seq(
      Dependency.of("io.circe", s"circe-generic_$scalaBinaryVersion", "0.14.1")
    )
  }

  check(
    "basic",
    """|val <<a>> = 123
       |<<@@a>> + 1  
       |""".stripMargin,
  )

  check(
    "generics",
    """|trait S1[X] { def <<torename>>(p: X): String = "" }
       |trait T1[Z] extends S1[Z] { override def <<torename>>(p: Z): String = super.<<torename>>(p) }
       |trait T2[X] extends T1[X] { override def <<torename>>(p: X): String = super.<<torename>>(p) }
       |trait T3[I, J] extends T2[I] { override def <<torename>>(p: I): String = super.<<torename>>(p) }
       |trait T4[I, J] extends T3[J, I] { override def <<torename>>(p: J): String = super.<<torename>>(p) }
       |trait T5[U] extends T4[U, U] { override def <<tore@@name>>(p: U): String = super.<<torename>>(p) }
       |""".stripMargin,
  )

  check(
    "match-ret-type",
    """|trait P
       |trait PP extends P
       |trait A { def <<torename>>(a: String): P = ??? }
       |trait B extends A { override def <<tore@@name>>(a: String): PP = ??? }
       |""".stripMargin,
  )

  check(
    "self-type",
    """|trait <<A@@BC>>
       |trait Alphabet{
       |  this: <<ABC>> =>
       |}
       |object A{
       |  val a = new Alphabet with <<ABC>>
       |}
       |""".stripMargin,
    newName = "Animal",
  )

  check(
    "method-inheritance",
    """|trait Hello{
       |  def <<method>>(abc : String) : Boolean
       |}
       |
       |class GoodMorning extends Hello {
       |  def <<met@@hod>>(abc : String) = true
       |}
       |""".stripMargin,
  )

  check(
    "long-inheritance",
    """|trait A[T, S] {
       |  def <<method>>(abc : T) : S
       |}
       |
       |abstract class B[T] extends A[T, Boolean] {
       |  def <<method>>(abc : T) : Boolean
       |}
       |
       |abstract class C extends B[String] {
       |  def <<meth@@od>>(abc : String) : Boolean = false
       |}
       |""".stripMargin,
  )

  check(
    "multiple-inheritance",
    """|trait A {
       |  def <<method>>(abc : String) : Boolean
       |}
       |
       |trait B {
       |  def <<method>>(abc : String) : Boolean = true
       |}
       |
       |abstract class C extends B with A {
       |  override def <<meth@@od>>(abc : String) : Boolean = false
       |}
       |""".stripMargin,
  )

  check(
    "colon-bad",
    """|class User{
       |  def <<:@@:>>(name : String) = name
       |}
       |object Main{
       |  val user = new User()
       |  "" <<::>> user
       |}
       |""".stripMargin,
  )

  check(
    "colon-good",
    """|class User{
       |  def <<:@@:>>(name : String) = name
       |}
       |object Main{
       |  val user = new User()
       |  "" <<::>> user
       |}
       |""".stripMargin,
    newName = "+++:",
  )

  check(
    "inheritance",
    """|abstract class <<An@@imal>>
       |class Dog extends <<Animal>>
       |class Cat extends <<Animal>>
       |""".stripMargin,
    newName = "Tree",
  )

  check(
    "companion",
    """|class <<Foo>>{}
       |object <<Fo@@o>> {}
  """.stripMargin,
    newName = "Tree",
  )
  check(
    "companion2",
    """|class <<Fo@@o>>{}
       |object <<Foo>>
  """.stripMargin,
    newName = "Tree",
  )

  check(
    "macro",
    """|import io.circe.generic.JsonCodec
       |trait LivingBeing
       |@JsonCodec sealed trait <<An@@imal>> extends LivingBeing
       |object <<Animal>> {
       |  case object Dog extends <<Animal>>
       |  case object Cat extends <<Animal>>
       |}
       |""".stripMargin,
  )

  check(
    "anon",
    """|trait Methodable[T] {
       |  def <<method>>(asf: T): Int
       |}
       |
       |trait Alphabet extends Methodable[String] {
       |  def <<method>>(adf: String) = 123
       |}
       |
       |val a = new Alphabet {
       |  override def <<me@@thod>>(adf: String): Int = 321
       |}
       |""".stripMargin,
  )

  check(
    "implicit-param",
    """|implicit val <<some@@Name>>: Int = 1
       |def m[A](implicit a: A): A = a
       |m[Int]
       |""".stripMargin,
  )

  check(
    "backtick-new-name",
    """|object A{
       |  val <<toRename>> = 123
       |}
       |object B{
       |  val toRename = A.<<toR@@ename>>
       |}
       |""".stripMargin,
    newName = "`other-rename`",
  )

  check(
    "backtick-old-and-new-name",
    """|object A{
       |  val <<`to-Rename`>> = 123
       |}
       |object B{
       |  val toRename = A.<<`to-R@@ename`>>
       |}
       |""".stripMargin,
    newName = "`other-rename`",
  )

  check(
    "backtick",
    """|val <<greet@@ing>> = "Hello"
       |"" match {
       |  case `<<greeting>>` =>
       |}
       |""".stripMargin,
  )

  check(
    "double-backtick",
    """|val <<greet@@ing>> = "Hello"
       |"" match {
       |  case <<`greeting`>> =>
       |}
       |""".stripMargin,
    newName = "`greeting-!`",
  )

  check(
    "backtick2",
    """|val <<greeting>> = "Hello"
       |"" match {
       |  case `<<gre@@eting>>` =>
       |}
       |""".stripMargin,
  )

  check(
    "backtick3",
    """|val greeting = "Hello"
       |"" match {
       |  case `gre@@eting` =>
       |}
       |""".stripMargin,
    newName = "`greeting`",
  )

  check(
    "params1",
    """|case class Name(<<value>>: String)
       |val name1 = Name(<<value>> = "42")
       | .copy(<<value>> = "43")
       | .copy(<<va@@lue>> = "43")
       | .<<value>>
       |val name2 = Name(<<value>> = "44")
       |""".stripMargin,
  )
  check(
    "params2",
    """|case class Name(<<value>>: String)
       |val name1 = Name(<<val@@ue>> = "42")
       | .copy(<<value>> = "43")
       | .copy(<<value>> = "43")
       | .<<value>>
       |val name2 = Name(<<value>> = "44")
       |""".stripMargin,
  )

  check(
    "constructor",
    """|case class Name(<<va@@lue>>: String)
       |val name2 = new Name(<<value>> = "44")
       |""".stripMargin,
  )

  check(
    "type-params1",
    """|trait <<ABC>>
       |class CBD[T <: <<ABC>>]
       |val a = classOf[<<AB@@C>>]
       |val b = new CBD[<<ABC>>]
       |""".stripMargin,
    newName = "Animal",
  )

  check(
    "type-params2",
    """|trait <<A@@BC>>
       |class CBD[T <: <<ABC>>]
       |val a = classOf[<<ABC>>]
       |val b = new CBD[<<ABC>>]
       |""".stripMargin,
    newName = "Animal",
  )

  check(
    "implicit-parameters",
    """|trait A {
       | implicit def <<foo>>: Double
       |}
       |object A extends A {
       |  implicit def <<fo@@o>>: Double = 0.1
       |  def bar(implicit x: Double): Double = x
       |  val x = bar
       |}
       |""".stripMargin,
    newName = "foo2",
  )

  check(
    "hierarchy-trait",
    """|sealed trait <<Sy@@mbol>>
       |case class Method(name: String) extends <<Symbol>>
       |case class Variable(value: String) extends <<Symbol>>
       |
       |val symbol2: <<Symbol>> = Method("method")
       |val symbol3: <<Symbol>> = Variable("value")
       |""".stripMargin,
    newName = "NewSymbol",
  )

  check(
    "hierarchy-class",
    """|sealed abstract class <<Sy@@mbol>>
       |case class Method(name: String) extends <<Symbol>>
       |case class Variable(value: String) extends <<Symbol>>
       |
       |val symbol2: <<Symbol>> = Method("method")
       |val symbol3: <<Symbol>> = Variable("value")
       |""".stripMargin,
    newName = "NewSymbol",
  )

  check(
    "variable",
    """|  var <<v@@5>> = false
       |
       |  def f5: Boolean = {
       |    <<v5>> = true
       |    <<v5>> == true
       |  }
       |""".stripMargin,
  )

  check(
    "worksheet-method",
    """|trait S1[X] { def <<torename>>(p: X): String = "" }
       |trait T1[Z] extends S1[Z] { override def <<tore@@name>>(p: Z): String = super.<<torename>>(p) }
       |""".stripMargin,
    filename = "A.worksheet.sc",
    wrap = false,
  )

  check(
    "worksheet-classes",
    """|sealed abstract class <<Sy@@mbol>>
       |case class Method(name: String) extends <<Symbol>>
       |case class Variable(value: String) extends <<Symbol>>
       |""".stripMargin,
    newName = "Tree",
    filename = "A.worksheet.sc",
    wrap = false,
  )

  check(
    "not-compiling",
    """|package a
       |object Main {
       |  def method() = {
       |    List(1) + 2
       |    val <<abc>>: Option[Int] = ???
       |    <<ab@@c>>.map(_ + 1)
       |  }
       |}
       |""".stripMargin,
  )

  check(
    "extension-param".tag(IgnoreScala2),
    """|extension (<<sb@@d>>: String)
       |  def double = <<sbd>> + <<sbd>>
       |  def double2 = <<sbd>> + <<sbd>>
       |end extension
       |""".stripMargin,
    newName = "greeting",
  )

  check(
    "extension-params-ref".tag(IgnoreScala2),
    """|extension (<<sbd>>: String)
       |  def double = <<sb@@d>> + <<sbd>>
       |  def double2 = <<sbd>> + <<sbd>>
       |end extension
       |""".stripMargin,
    newName = "greeting",
  )

  check(
    "extension-type-param".tag(IgnoreScala2),
    """|extension [T](<<x@@s>>: List[T])
       |  def double = <<xs>> ++ <<xs>>
       |  def double2 = <<xs>> ++ <<xs>>
       |end extension
       |""".stripMargin,
    newName = "ABC",
  )

  check(
    "extension-type-param-ref".tag(IgnoreScala2),
    """|extension [T](<<xs>>: List[T])
       |  def double = <<xs>> ++ <<xs>>
       |  def double2 = <<xs>> ++ <<x@@s>>
       |end extension
       |""".stripMargin,
    newName = "ABC",
  )
}
