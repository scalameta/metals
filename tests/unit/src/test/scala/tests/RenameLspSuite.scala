package tests

import scala.meta.internal.metals.InitializationOptions

class RenameLspSuite extends BaseRenameLspSuite(s"rename") {

  override protected def initializationOptions: Option[InitializationOptions] =
    Some(TestingServer.TestDefault)

  renamed(
    "basic",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |object Main{
       |  val <<toRename>> = 123
       |}
       |/a/src/main/scala/a/Main2.scala
       |package a
       |object Main2{
       |  val toRename = Main.<<toR@@ename>>
       |}
       |""".stripMargin,
    newName = "otherRename",
  )

  renamed(
    "renamed-import",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |
       |import java.util.{List => <<`J-List`>>}
       |
       |object Main{
       |  val toRename: <<`J-L@@ist`>>[Int] = ???
       |  val toRename2: <<`J-List`>>[Int] = ???
       |  val toRename3: java.util.List[Int] = ???
       |}
       |/a/src/main/scala/a/Main2.scala
       |package a
       |
       |import java.util.{List => JList}
       |
       |object Main2{
       |  val toRename: JList[Int] = ???
       |}
       |""".stripMargin,
    newName = "Java-List",
  )

  renamed(
    "renamed-import-local",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |
       |import a.{Main2 => <<Ot@@herMain>>}
       |
       |object Main{
       |  val toRename = <<OtherMain>>.toRename
       |}
       |/a/src/main/scala/a/Main2.scala
       |package a
       |
       |object Main2{
       |  val toRename = ""
       |}
       |""".stripMargin,
    newName = "OtherM",
  )

  renamed(
    "case",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |case class <<User>>(name : String)
       |object Main{
       |  val user = <<User>>.apply("James")
       |  val user2 = <<U@@ser>>(name = "Roger")
       |  user.copy(name = "")
       |}
       |""".stripMargin,
    newName = "Login",
  )

  renamed(
    "generics",
    """/a/src/main/scala/a/Main.scala
      |package a
      |trait S1[X] { def <<torename>>(p: X): String = "" }
      |trait T1[Z] extends S1[Z] { override def <<torename>>(p: Z): String = super.<<torename>>(p) }
      |trait T2[X] extends T1[X] { override def <<torename>>(p: X): String = super.<<torename>>(p) }
      |trait T3[I, J] extends T2[I] { override def <<torename>>(p: I): String = super.<<torename>>(p) }
      |trait T4[I, J] extends T3[J, I] { override def <<torename>>(p: J): String = super.<<torename>>(p) }
      |trait T5[U] extends T4[U, U] { override def <<tore@@name>>(p: U): String = super.<<torename>>(p) }
      |""".stripMargin,
    newName = "newname",
  )

  renamed(
    "match-ret-type",
    """/a/src/main/scala/a/Main.scala
      |package a
      |trait P
      |trait PP extends P
      |trait A { def <<torename>>(a: String): P = ??? }
      |trait B extends A { override def <<tore@@name>>(a: String): PP = ??? }
      |
      |""".stripMargin,
    newName = "newname",
  )

  renamed(
    "across-targets",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |object Main{
       |  val <<toRename>> = 123
       |}
       |/b/src/main/scala/b/Main2.scala
       |package b
       |import a.Main
       |object Main2{
       |  val toRename = Main.<<toR@@ename>>
       |}
       |""".stripMargin,
    newName = "otherRename",
  )

  renamed(
    "unapply",
    """|/a/src/main/scala/a/Main.scala
       |object <<F@@oo>> {
       |  def unapply(s: String): Option[String] = Some("")
       |}
       |
       |object Main{
       |  "foo" match {
       |    case <<Foo>>(s) => ()
       |  }
       |}
       |""".stripMargin,
    newName = "Bar",
  )

  renamed(
    "unapply-param",
    """|/a/src/main/scala/a/Main.scala
       |object Foo {
       |  def unapply(<<nam@@e>>: String): Option[String] = Some(<<name>>)
       |}
       |
       |object Main{
       |  "foo" match {
       |    case Foo(name) => ()
       |  }
       |}
       |""".stripMargin,
    newName = "str",
  )

  renamed(
    "local",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |object Main{
       |  def hello() = {
       |    val <<toRen@@ame>> = 123
       |    <<toRename>>
       |  }
       |}
       |""".stripMargin,
    newName = "otherRename",
  )

  renamed(
    "method",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |object Main{
       |  def <<met@@hod>>(abc : String) = true
       |
       |  if(<<method>>("")) println("Is true!")
       |}
       |""".stripMargin,
    newName = "truth",
  )

  renamed(
    "self-type",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |trait <<A@@BC>>
       |trait Alphabet{
       |  this: <<ABC>> =>
       |}
       |object Main{
       |  val a = new Alphabet with <<ABC>>
       |}
       |""".stripMargin,
    newName = "Animal",
  )

  renamed(
    "method-inheritance",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |trait Hello{
       |  def <<method>>(abc : String) : Boolean
       |}
       |
       |class GoodMorning extends Hello {
       |  def <<met@@hod>>(abc : String) = true
       |}
       |""".stripMargin,
    newName = "truth",
  )

  renamed(
    "long-inheritance",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |trait A[T, S] {
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
    newName = "truth",
  )

  renamed(
    "multiple-inheritance",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |trait A {
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
    newName = "truth",
  )

  renamed(
    "apply",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |object User{
       |  def <<ap@@ply>>(name : String) = name
       |  def apply(name : String, age: Int) = name
       |}
       |object Main{
       |  val toRename = User##.##<<>>("abc")
       |}
       |""".stripMargin,
    newName = "name",
  )

  same(
    "colon-bad",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |class User{
       |  def <<:@@:>>(name : String) = name
       |}
       |object Main{
       |  val user = new User()
       |  "" <<::>> user
       |}
       |""".stripMargin,
  )

  renamed(
    "colon-good",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |class User{
       |  def <<:@@:>>(name : String) = name
       |}
       |object Main{
       |  val user = new User()
       |  "" <<::>> user
       |}
       |""".stripMargin,
    newName = "+++:",
  )

  same(
    "unary-bad",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |class User{
       |  def <<unary_!>> = false
       |}
       |object Main{
       |  val user = new User()
       |  <<@@!>>user
       |}
       |""".stripMargin,
  )

  same(
    "unary-bad2",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |class User{
       |  def <<u@@nary_!>> = false
       |}
       |object Main{
       |  val user = new User()
       |  <<!>>user
       |}
       |""".stripMargin,
  )

  same(
    "java-classes",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |class MyException extends Exce@@ption
       |class NewException extends RuntimeException
       |class NewException2 extends RuntimeException
       |""".stripMargin,
  )

  renamed(
    "inheritance",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |abstract class <<An@@imal>>
       |class Dog extends <<Animal>>
       |class Cat extends <<Animal>>
       |""".stripMargin,
    newName = "Tree",
  )

  renamed(
    "companion",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |class <<Main>>{}
       |object <<M@@ain>>
       |""".stripMargin,
    newName = "Tree",
    fileRenames =
      Map("a/src/main/scala/a/Main.scala" -> "a/src/main/scala/a/Tree.scala"),
  )

  renamed(
    "companion2",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |class <<Ma@@in>>{}
       |object <<Main>>
       |""".stripMargin,
    newName = "Tree",
    fileRenames =
      Map("a/src/main/scala/a/Main.scala" -> "a/src/main/scala/a/Tree.scala"),
  )

  renamed(
    "filename-exact-match",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |object <<Ma@@in>>
       |object TheMain
       |""".stripMargin,
    newName = "Tree",
    fileRenames =
      Map("a/src/main/scala/a/Main.scala" -> "a/src/main/scala/a/Tree.scala"),
  )

  renamed(
    "filename-exact-match-2",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |object Main
       |object <<The@@Main>>
       |""".stripMargin,
    newName = "Tree",
    fileRenames = Map.empty,
  )

  renamed(
    "many-files",
    """|/a/src/main/scala/a/A.scala
       |package a
       |object A {
       |  def <<ren@@ameIt>>(a : String) = ""
       |}
       |/a/src/main/scala/a/B.scala
       |package a
       |object B {
       |  val str = A.<<renameIt>>("")
       |}
       |/a/src/main/scala/a/C.scala
       |package a
       |object C {
       |  val str = A.<<renameIt>>("")
       |}
       |/a/src/main/scala/a/D.scala
       |package a
       |object D {
       |  val str = A.<<renameIt>>("")
       |}
       |/a/src/main/scala/a/E.scala
       |package a
       |object E {
       |  val str = A.<<renameIt>>("")
       |}
       |""".stripMargin,
    newName = "iAmRenamed",
    nonOpened = Set(
      "a/src/main/scala/a/C.scala",
      "a/src/main/scala/a/D.scala",
      "a/src/main/scala/a/E.scala",
    ),
  )

  renamed(
    "anon",
    """|/a/src/main/scala/a/Main.scala
       |trait Methodable[T] {
       |  def <<method>>(asf: T): Int
       |}
       |
       |trait Alphabet extends Methodable[String] {
       |  def <<method>>(adf: String) = 123
       |}
       |
       |object Main {
       |  val a = new Alphabet {
       |    override def <<me@@thod>>(adf: String): Int = 321
       |  }
       |}
       |""".stripMargin,
    newName = "renamed",
  )

  renamed(
    "java-changed",
    """|/a/src/main/java/a/Other.java
       |package a;
       |public class <<Other>>{
       |
       |  <<Other>> other;
       |  public <<Other>>(){
       |     
       |  }
       |}
       |/a/src/main/scala/a/Main.scala
       |package a
       |object Main{
       |  val other = new <<Oth@@er>>()
       |}
       |""".stripMargin,
    newName = "Renamed",
  )

  renamed(
    "java-only",
    """|/a/src/main/java/a/Other.java
       |package a;
       |public class <<Other>>{
       |
       |  <<Ot@@her>> other;
       |  public <<Other>>(){
       |     
       |  }
       |}
       |/a/src/main/scala/a/Main.scala
       |package a
       |object Main{
       |  val other = new <<Oth@@er>>()
       |}
       |""".stripMargin,
    newName = "Renamed",
  )

  renamed(
    "compile-error",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |object Main{
       |  val <<toRename>> : Int = 123
       |}
       |/a/src/main/scala/a/Main2.scala
       |package a
       |object Main2{
       |  val toRename = Main.<<toR@@ename>>
       |}
       |""".stripMargin,
    newName = "otherRename",
    breakingChange = (str: String) => str.replaceAll("Int", "String"),
    expectedError = true,
  )

  renamed(
    "macro",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |import io.circe.generic.JsonCodec
       |trait LivingBeing
       |@JsonCodec sealed trait <<An@@imal>> extends LivingBeing
       |object <<Animal>> {
       |  case object Dog extends <<Animal>>
       |  case object Cat extends <<Animal>>
       |}
       |""".stripMargin,
    "Tree",
  )

  renamed(
    "macro1",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |import io.circe.generic.JsonCodec
       |trait <<LivingBeing>>
       |@JsonCodec sealed trait Animal extends <<Livi@@ngBeing>>
       |object Animal {
       |  case object Dog extends Animal
       |  case object Cat extends Animal
       |}
       |""".stripMargin,
    "Tree",
  )

  renamed(
    "macro2",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |import io.circe.generic.JsonCodec
       |@JsonCodec
       |final case class <<Ma@@in2>>(name: String)
       |""".stripMargin,
    "Tree",
  )

  renamed(
    "macro3",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |import io.circe.generic.JsonCodec
       |trait LivingBeing
       |@JsonCodec sealed trait <<Animal>> extends LivingBeing
       |object <<Animal>>{
       |  case object Dog extends <<Animal>>
       |  case object Cat extends <<Animal>>
       |}
       |/a/src/main/scala/a/Use.scala
       |package a
       |object Use {
       |  val dog : <<An@@imal>> = <<Animal>>.Dog
       |}
       |""".stripMargin,
    "Tree",
  )

  renamed(
    "implicit-param",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |object A {
       |  implicit val <<some@@Name>>: Int = 1
       |  def m[A](implicit a: A): A = a
       |  m[Int]
       |}""".stripMargin,
    newName = "anotherName",
  )

  renamed(
    "nested-symbol",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |object Foo {
       |  object <<Ma@@in>>
       |}
       |""".stripMargin,
    newName = "Child",
    fileRenames = Map.empty,
  )

  renamed(
    "backtick-new-name",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |object Main{
       |  val <<toRename>> = 123
       |}
       |/a/src/main/scala/a/Main2.scala
       |package a
       |object Main2{
       |  val toRename = Main.<<toR@@ename>>
       |}
       |""".stripMargin,
    newName = "other-rename",
  )

  renamed(
    "backtick-old-and-new-name",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |object Main{
       |  val <<`to-Rename`>> = 123
       |}
       |object Main2{
       |  val toRename = Main.<<`to-R@@ename`>>
       |}
       |""".stripMargin,
    newName = "`other-rename`",
  )

  renamed(
    "backtick",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |object Main{
       |  val <<greet@@ing>> = "Hello"
       |  "" match {
       |    case `<<greeting>>` =>
       |  }
       |}
       |""".stripMargin,
    newName = "other",
  )

  renamed(
    "double-backtick",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |object Main{
       |  val <<greet@@ing>> = "Hello"
       |  "" match {
       |    case <<`greeting`>> =>
       |  }
       |}
       |""".stripMargin,
    newName = "greeting-!",
  )

  renamed(
    "backtick2",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |object Main{
       |  val <<greeting>> = "Hello"
       |  "" match {
       |    case `<<gre@@eting>>` =>
       |  }
       |}
       |""".stripMargin,
    newName = "other",
  )

  renamed(
    "backtick3",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |object Main{
       |  def local = {
       |    val <<greet@@ing>> = "Hello"
       |    "" match {
       |      case `<<greeting>>` =>
       |    }
       |  }
       |}
       |""".stripMargin,
    newName = "other",
  )

  // If renaming in VS Code, backticks are taken as part of the name
  renamed(
    "backtick4",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |object Main{
       |  def local = {
       |    val greeting = "Hello"
       |    "" match {
       |      case `gre@@eting` =>
       |    }
       |  }
       |}
       |""".stripMargin,
    newName = "`greeting`",
  )

  renamed(
    "params",
    """|/a/src/main/scala/a/Main.scala
       |case class Name(<<va@@lue>>: String)
       |
       |object Main {
       |  val name1 = Name(<<value>> = "42")
       |   .copy(<<value>> = "43")
       |   .copy(<<value>> = "43")
       |   .<<value>>
       |  val name2 = Name(<<value>> = "44")
       |}
       |""".stripMargin,
    newName = "name",
  )

  renamed(
    "constructor",
    """|/a/src/main/scala/a/Main.scala
       |case class Name(<<va@@lue>>: String)
       |
       |object Main {
       |  val name2 = new Name(<<value>> = "44")
       |}
       |""".stripMargin,
    newName = "name",
  )

  renamed(
    "type-params",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |trait <<ABC>>
       |class CBD[T <: <<AB@@C>>]
       |object Main{
       |  val a = classOf[<<ABC>>]
       |  val b = new CBD[<<ABC>>]
       |}
       |""".stripMargin,
    newName = "Animal",
  )

  renamed(
    "implicit-parameter",
    """|/a/src/main/scala/a/Main.scala
       |trait A {
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

  renamed(
    "ignores-unrelated-build-targets",
    """|/a/src/main/scala/a/Main.scala
       |trait <<@@A>>
       |/a/src/main/scala/a/B.scala
       |trait B extends <<A>>
       |/b/src/main/scala/b/Main.scala
       |trait A
       |/b/src/main/scala/b/B.scala
       |trait B extends A
       |""".stripMargin,
    metalsJson = Some(
      s"""|{
          |  "a" : {
          |    "scalaVersion": "${BuildInfo.scalaVersion}"
          |  },
          |  "b" : {
          |    "scalaVersion": "${BuildInfo.scalaVersion}"
          |  }
          |}""".stripMargin
    ),
    newName = "C",
  )

  renamed(
    "hierarchy-inside-method-trait",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |object Main {
       |  final def main(args: Array[String]) = {
       |    sealed trait <<Sy@@mbol>>
       |    case class Method(name: String) extends <<Symbol>>
       |    case class Variable(value: String) extends <<Symbol>>
       |
       |    val symbol2: <<Symbol>> = Method("method")
       |    val symbol3: <<Symbol>> = Variable("value")
       |  }
       |}
       |""".stripMargin,
    newName = "NewSymbol",
  )

  renamed(
    "hierarchy-inside-method-class",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |object Main {
       |  final def main(args: Array[String]) = {
       |    sealed abstract class <<Sy@@mbol>>
       |    case class Method(name: String) extends <<Symbol>>
       |    case class Variable(value: String) extends <<Symbol>>
       |
       |    val symbol2: <<Symbol>> = Method("method")
       |    val symbol3: <<Symbol>> = Variable("value")
       |  }
       |}
       |""".stripMargin,
    newName = "NewSymbol",
  )

  renamed(
    "variable",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |object Main {
       |  var <<v@@5>> = false
       |
       |  def f5: Boolean = {
       |    <<v5>> = true
       |    <<v5>> == true
       |  }
       |}
       |""".stripMargin,
    newName = "NewSymbol",
  )

  renamed(
    "variable-explicit1",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |object Main {
       |  var <<v@@5>> = false
       |
       |  def f5: Boolean = {
       |    <<v5>>_=(true)
       |    <<v5>> == true
       |  }
       |}
       |""".stripMargin,
    newName = "NewSymbol",
  )

  renamed(
    "variable-explicit2",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |object Main {
       |  var <<v5>> = false
       |
       |  def f5: Boolean = {
       |    `<<v@@5>>_=`(true)
       |    <<v5>> == true
       |  }
       |}
       |""".stripMargin,
    newName = "NewSymbol",
  )

  override protected def libraryDependencies: List[String] =
    List("org.scalatest::scalatest:3.2.12", "io.circe::circe-generic:0.14.1")

}
