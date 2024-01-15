package tests.highlight

import tests.BaseDocumentHighlightSuite

class DocumentHighlightSuite extends BaseDocumentHighlightSuite {

  check(
    "single",
    """
      |object Main {
      |  Option(1).<<he@@ad>>
      |}""".stripMargin
  )

  check(
    "multiple",
    """
      |object Main {
      |  val <<abc>> = 123
      |  <<abc>>.toInt
      |  println(<<ab@@c>>)
      |}""".stripMargin
  )
  check(
    "multiple2",
    """
      |object Main {
      |  val <<a@@bc>> = 123
      |  <<abc>>.toInt
      |  println(<<abc>>)
      |}""".stripMargin
  )

  check(
    "multiple3",
    """
      |object Main {
      |  val <<abc>> = 123
      |  <<ab@@c>>.toInt
      |  println(<<abc>>)
      |}""".stripMargin
  )

  check(
    "different-symbols",
    """
      |object Main {
      |  val abc = 123
      |  abc.<<to@@Int>>
      |  134l.toInt
      |}""".stripMargin
  )

  check(
    "named-arg",
    """
      |case class Foo(foo: Int, <<bar>>: Int)
      |object Main {
      |  val x = Foo(
      |    foo = 123,
      |    <<ba@@r>> = 456
      |  ) 
      |
      |}""".stripMargin
  )

  check(
    "named-arg1",
    """
      |case class Foo(<<foo>>: Int, bar: Int)
      |object Main {
      |  val x = Foo(
      |    <<fo@@o>> = 123,
      |    bar = 456
      |  )
      |  val y = x.<<foo>>
      |  
      |
      |}""".stripMargin
  )

  check(
    "named-args2".tag(IgnoreScala211),
    """
      |object Main {
      |  def foo = check("abc")(
      |    directory = "foo",
      |    <<file@@name>> = 1,
      |  )
      |
      |  private def check(name: String)(
      |    directory: String,
      |    <<filename>>: Int,
      |    otherDefault: String = "default"
      |  ): Unit = ()
      |}""".stripMargin
  )

  check(
    "named-args2".tag(IgnoreScala211),
    """
      |object Main {
      |  def foo = check("abc")(
      |    directory = "foo",
      |    <<file@@name>> = 1,
      |  )
      |
      |  private def check(name: String)(
      |    directory: String,
      |    <<filename>>: Int,
      |    otherDefault: String = "default"
      |  ): Unit = ()
      |}""".stripMargin
  )

  check(
    "named-args3",
    """
      |package example
      |
      |object Main {
      |  check("abc")(
      |    directory = Some("foo"),
      |    <<filename>> = 1
      |  )(loc = true)
      |
      |  private def check(name: String)(
      |      directory: Option[String],
      |      <<fil@@ename>>: Int,
      |      otherDefault: String = "default"
      |  )(loc: Boolean): Unit = ()
      |}""".stripMargin
  )

  check(
    "scopes",
    """
      |object Main {
      |  val <<@@a>> = 123
      |  val f = (a: Int) => a + 1
      |  println(<<a>>)
      |}""".stripMargin
  )

  check(
    "scopes2",
    """
      |object Main {
      |  val <<a>> = 123
      |  val f = (a: Int) => a + 1
      |  println(<<@@a>>)
      |}""".stripMargin
  )

  check(
    "params",
    """
      |case class User(<<n@@ame>>: String)
      |object Main {
      |  val user = User(<<name>> = "Susan")
      |  println(user.<<name>>)
      |  user.copy(<<name>> = "John")
      |}""".stripMargin
  )

  check(
    "params2",
    """
      |case class User(<<name>>: String)
      |object Main {
      |  val user = User(<<na@@me>> = "Susan")
      |  println(user.<<name>>)
      |  user.copy(<<name>> = "John")
      |}""".stripMargin
  )

  check(
    "params3",
    """
      |case class User(<<name>>: String)
      |object Main {
      |  val user = User(<<name>> = "Susan")
      |  println(user.<<n@@ame>>)
      |  user.copy(<<name>> = "John")
      |}""".stripMargin
  )

  check(
    "params4",
    """
      |case class User(<<name>>: String)
      |object Main {
      |  val user = User(<<name>> = "Susan")
      |  println(user.<<name>>)
      |  user.copy(<<na@@me>> = "John")
      |}""".stripMargin
  )

  check(
    "object",
    """
      |case class <<U@@ser>>(name: String)
      |object <<User>>
      |object Main {
      |  val user = <<User>>(name = "Susan")
      |  println(user.name)
      |  user.copy(name = "John")
      |}""".stripMargin
  )

  check(
    "object2",
    """
      |case class <<User>>(name: String)
      |object <<Us@@er>>
      |object Main {
      |  val user = <<User>>(name = "Susan")
      |  println(user.name)
      |  user.copy(name = "John")
      |}""".stripMargin
  )

  check(
    "object3",
    """
      |case class <<User>>(name: String)
      |object <<User>>
      |object Main {
      |  val user = <<U@@ser>>(name = "Susan")
      |  println(user.name)
      |  user.copy(name = "John")
      |}""".stripMargin
  )

  check(
    "case-class-var",
    """
      |case class User(var <<na@@me>>: String)
      |object Main {
      |  val user = User(<<name>> = "Susan")
      |  println(user.<<name>>)
      |  user.<<name>> = ""
      |  user.copy(<<name>> = "John")
      |}""".stripMargin
  )

  check(
    "case-class-var2",
    """
      |case class User(var <<name>>: String)
      |object Main {
      |  val user = User(<<na@@me>> = "Susan")
      |  println(user.<<name>>)
      |  user.<<name>> = ""
      |  user.copy(<<name>> = "John")
      |}""".stripMargin
  )

  check(
    "case-class-var3",
    """
      |case class User(var <<name>>: String)
      |object Main {
      |  val user = User(<<name>> = "Susan")
      |  println(user.<<n@@ame>>)
      |  user.<<name>> = ""
      |  user.copy(<<name>> = "John")
      |}""".stripMargin
  )

  check(
    "case-class-var4",
    """
      |case class User(var <<name>>: String)
      |object Main {
      |  val user = User(<<name>> = "Susan")
      |  println(user.<<name>>)
      |  user.<<na@@me>> = ""
      |  user.copy(<<name>> = "John")
      |}""".stripMargin
  )

  check(
    "case-class-var5",
    """
      |case class User(var <<name>>: String)
      |object Main {
      |  val user = User(<<name>> = "Susan")
      |  println(user.<<name>>)
      |  user.<<name>> = ""
      |  user.copy(<<na@@me>> = "John")
      |}""".stripMargin
  )

  check(
    "var",
    """
      |object Main {
      |  var <<ab@@d>> = 123
      |  <<abd>> = 344
      |  <<abd>> +=1
      |  println(<<abd>>)
      |}""".stripMargin
  )

  check(
    "var2",
    """
      |object Main {
      |  var <<abd>> = 123
      |  <<ab@@d>> = 344
      |  <<abd>> +=1
      |  println(<<abd>>)
      |}""".stripMargin
  )

  check(
    "var3",
    """
      |object Main {
      |  var <<abd>> = 123
      |  <<abd>> = 344
      |  <<ab@@d>> +=1
      |  println(<<abd>>)
      |}""".stripMargin
  )

  check(
    "var4",
    """
      |object Main {
      |  var <<abd>> = 123
      |  <<abd>> = 344
      |  <<abd>> +=1
      |  println(<<a@@bd>>)
      |}""".stripMargin
  )

  check(
    "overloaded",
    """
      |object Main {
      |  def hello() = ""
      |  def <<hel@@lo>>(a : Int) = ""
      |  def hello(a : Int, b : String) = ""
      |}""".stripMargin
  )

  check(
    "local-var",
    """
      |object Test {
      |  def met() = {
      |    class T1(var abc: Int) {
      |       class T2(var <<a@@bc>>: Int) {
      |          <<abc>> = 4
      |          def m3: Int = <<abc>> + 2
      |      }
      |      abc = 4
      |      def m2: Int = abc + 2
      |    }
      |  }
      |}""".stripMargin
  )

  check(
    "local-var2",
    """
      |object Test {
      |  def met() = {
      |    class T1(var abc: Int) {
      |       class T2(var <<abc>>: Int) {
      |          <<ab@@c>> = 4
      |          def m3: Int = <<abc>> + 2
      |      }
      |      abc = 4
      |      def m2: Int = abc + 2
      |    }
      |  }
      |}""".stripMargin
  )

  check(
    "local-var3",
    """
      |object Test {
      |  def met() = {
      |    class T1(var abc: Int) {
      |       class T2(var <<abc>>: Int) {
      |          <<abc>> = 4
      |          def m3: Int = <<a@@bc>> + 2
      |      }
      |      abc = 4
      |      def m2: Int = abc + 2
      |    }
      |  }
      |}""".stripMargin
  )

  check(
    "local-assign",
    """
      |object Test {
      |  def met() = {
      |    class T1(var abc: Int) {
      |       class T2(var <<ab@@c>>: Int) {
      |          <<abc>> = 4
      |          def m3: Int = <<abc>> + 2
      |      }
      |      abc = 4
      |      def m2: Int = abc + 2
      |    }
      |  }
      |}""".stripMargin
  )
  check(
    "local-assign2",
    """
      |object Test {
      |  def met() = {
      |    class T1(var abc: Int) {
      |       class T2(var <<abc>>: Int) {
      |          <<a@@bc>> = 4
      |          def m3: Int = <<abc>> + 2
      |      }
      |      abc = 4
      |      def m2: Int = abc + 2
      |    }
      |  }
      |}""".stripMargin
  )
  check(
    "local-assign3",
    """
      |object Test {
      |  def met() = {
      |    class T1(var abc: Int) {
      |       class T2(var <<abc>>: Int) {
      |          <<abc>> = 4
      |          def m3: Int = <<a@@bc>> + 2
      |      }
      |      abc = 4
      |      def m2: Int = abc + 2
      |    }
      |  }
      |}""".stripMargin
  )

  check(
    "local-class",
    """
      |object Test {
      |  def met() = {
      |    class T1(var abc: Int) {
      |       class T2(var <<ab@@c>>: Int) {
      |          <<abc>> = 4
      |          def m3: Int = <<abc>> + 2
      |      }
      |      abc = 4
      |      def m2: Int = abc + 2
      |    }
      |  }
      |}""".stripMargin
  )

  check(
    "local-class2",
    """
      |object Test {
      |  def met() = {
      |    class T1(var abc: Int) {
      |       class T2(var <<abc>>: Int) {
      |          <<a@@bc>> = 4
      |          def m3: Int = <<abc>> + 2
      |      }
      |      abc = 4
      |      def m2: Int = abc + 2
      |    }
      |  }
      |}""".stripMargin
  )

  check(
    "local-class3",
    """
      |object Test {
      |  def met() = {
      |    class T1(var abc: Int) {
      |       class T2(var <<abc>>: Int) {
      |          <<abc>> = 4
      |          def m3: Int = <<a@@bc>> + 2
      |      }
      |      abc = 4
      |      def m2: Int = abc + 2
      |    }
      |  }
      |}""".stripMargin
  )

  check(
    "setter-getter",
    """
      |object Test {
      |  class T1{
      |    def <<ar@@g_=>> (arg: Int) = {}
      |    def <<arg>> = 1
      |  }
      |  val t = new T1
      |  t.<<arg>> = 123
      |}""".stripMargin
  )

  check(
    "setter-getter2",
    """
      |object Test {
      |  class T1{
      |    def <<arg_=>> (arg: Int) = {}
      |    def <<a@@rg>> = 1
      |  }
      |  val t = new T1
      |  t.<<arg>> = 123
      |
      |}""".stripMargin
  )

  check(
    "setter-getter3",
    """
      |object Test {
      |  class T1{
      |    def <<arg_=>> (arg: Int) = {}
      |    def <<arg>> = 1
      |  }
      |  val t = new T1
      |  t.<<ar@@g>> = 123
      |}""".stripMargin
  )

  check(
    "same-name",
    """
      |object Test {
      |  def foo(name: String) = ???
      |  def bar(<<n@@ame>>: String) = ???
      |  foo(name = "123")
      |  bar(<<name>> = "123")
      |}""".stripMargin
  )

  check(
    "same-name2",
    """
      |object Test {
      |  def foo(name: String) = ???
      |  def bar(<<name>>: String) = ???
      |  foo(name = "123")
      |  bar(<<na@@me>> = "123")
      |}""".stripMargin
  )

  check(
    "same-name3",
    """
      |object Test {
      |  def foo(<<na@@me>>: String) = ???
      |  def bar(name: String) = ???
      |  foo(<<name>> = "123")
      |  bar(name = "123")
      |}""".stripMargin
  )

  check(
    "same-name4",
    """
      |object Test {
      |  def foo(<<name>>: String) = ???
      |  def bar(name: String) = ???
      |  foo(<<na@@me>> = "123")
      |  bar(name = "123")
      |}""".stripMargin
  )

  check(
    "import1",
    """
      |import scala.util.<<Tr@@y>>
      |object Test {
      |   <<Try>>(1)
      |}""".stripMargin
  )

  check(
    "import2",
    """
      |import scala.util.<<Try>>
      |object Test {
      |   <<Tr@@y>>(1)
      |}""".stripMargin
  )

  check(
    "import3",
    """
      |import scala.<<ut@@il>>.Try
      |object Test {
      |   scala.<<util>>.Try(1)
      |}""".stripMargin
  )

  check(
    "import4",
    """
      |import scala.<<util>>.Try
      |object Test {
      |   scala.<<ut@@il>>.Try(1)
      |}""".stripMargin
  )

  check(
    "rename1",
    """
      |import scala.util.{ <<Try>> => <<ATr@@y>>}
      |object Test {
      |   <<ATry>>(1)
      |}""".stripMargin
  )

  check(
    "rename2",
    """
      |import scala.util.{ <<Try>> => <<ATry>>}
      |object Test {
      |   <<ATr@@y>>(1)
      |}""".stripMargin
  )

  // @note, we could try and not highlight normal Try,
  // but this might still be useful
  check(
    "rename3",
    """
      |import scala.util.{ <<Try>> => <<ATr@@y>>}
      |object Test {
      |   scala.util.<<Try>>(1)
      |}""".stripMargin
  )

  check(
    "rename4",
    """
      |import scala.util.{ <<Try>> => <<ATry>>}
      |object Test {
      |   scala.util.<<Tr@@y>>(1)
      |}""".stripMargin
  )

  check(
    "rename5",
    """
      |import scala.util.{ <<T@@ry>> => <<ATry>>}
      |object Test {
      |   scala.util.<<Try>>(1)
      |}""".stripMargin
  )

  check(
    "case-match1",
    """
      |import scala.util.Try
      |import scala.util.Success
      |object Test {
      |   Try(1) match {
      |     case Success(<<va@@lue>>) =>
      |       <<value>>
      |   }
      |}""".stripMargin
  )

  check(
    "case-match2",
    """
      |import scala.util.Try
      |import scala.util.Success
      |object Test {
      |   Try(1) match {
      |     case Success(<<value>>) =>
      |       <<va@@lue>>
      |   }
      |}""".stripMargin
  )

  check(
    "inner-class1",
    """|object Main {
       |  def foo = {
       |    case class <<U@@ser>>(name: String)
       |    object <<User>>{ def nnn = ""}
       |    <<User>>.nnn
       |  }
       |}""".stripMargin
  )

  check(
    "inner-class2",
    """|object Main {
       |  def foo = {
       |    case class <<User>>(name: String)
       |    object <<U@@ser>>{ def nnn = ""}
       |    <<User>>.nnn
       |  }
       |}""".stripMargin
  )

  check(
    "inner-class3",
    """|object Main {
       |  def foo = {
       |    case class <<User>>(name: String)
       |    object <<User>>{ def nnn = ""}
       |    <<Use@@r>>.nnn
       |  }
       |}""".stripMargin
  )

  check(
    "inner-class4",
    """|object Main {
       |  def foo = {
       |    object O {
       |      case class <<User>>(name: String)
       |      object <<User>>{ def nnn = ""}
       |      <<Use@@r>>.nnn
       |    }
       |  }
       |}""".stripMargin
  )

  check(
    // Scala 2.12.x has a bug where the namePos points at `object`
    // working around it would involve a lot of additional logic
    "package-object".tag(IgnoreScalaVersion.forLessThan("2.13.0")),
    """|package example
       |
       |package object <<nes@@ted>> {
       |
       |  class PackageObjectNestedClass
       |
       |}
       |""".stripMargin
  )

  check(
    "named-param",
    """|object Main {
       |  def foo = {
       |      case class User(<<name>>: String)
       |      val a = User(<<na@@me>> = "abc")
       |  }
       |}""".stripMargin
  )

  check(
    "backtick",
    """|object Main {
       |  val <<`hi-!`>> = 5
       |
       |  <<`hi@@-!`>> + 3
       |}""".stripMargin
  )

  check(
    "shadowing",
    """|object Main {
       |  val abc = {
       |    val <<abc>> = 1
       |    <<a@@bc>> + 1
       |  }
       |  val d = abc + 1
       |}""".stripMargin
  )

  check(
    "select-parentheses",
    """|object Main {
       |  val a = (1 + 2 + 3).<<toStr@@ing>>
       |}""".stripMargin
  )

  check(
    "select-parentheses2".tag(IgnoreScala2),
    """|object Main {
       |  val a = (1 + 2 + 3) <<:@@:>> Nil
       |}""".stripMargin
  )

  check(
    "trailling-comma".tag(IgnoreScala2),
    """
      |object Main {
      |  val a = 1
      |  val <<b>> = 2
      |  List(
      |    a,
      |    <<b@@>>,
      |  )
      |}""".stripMargin
  )
  check(
    "trailling-comma2".tag(IgnoreScala2),
    """
      |object Main {
      |  val a = 1
      |  val <<`ab`>> = 2
      |  List(
      |    a,
      |    <<`ab@@`>>,
      |  )
      |}""".stripMargin
  )

  check(
    "for-comp-bind",
    """
      |object Main {
      |  case class Bar(fooBar: Int, goo: Int)
      |  val abc = for {
      |    foo <- List(1)
      |    _ = Option(1)
      |    Bar(<<fooBar>>, goo) <- List(Bar(foo, 123))
      |    baz = <<fooBar>> + goo
      |  } yield {
      |    val x = foo + <<foo@@Bar>> + baz
      |    x
      |  }
      |}""".stripMargin
  )

  check(
    "for-comp-bind2",
    """
      |object Main {
      |  val abc = for {
      |    <<f@@oo>> <- List(1)
      |    baz = <<foo>> + 1
      |    a <- List(<<foo>>, 123)
      |  } yield {
      |    val x = <<foo>> + baz
      |    x
      |  }
      |}""".stripMargin
  )

  check(
    "for-comp-map",
    """|object Main {
       |  val x = List(1).<<m@@ap>>(_ + 1)
       |  val y = for {
       |    a <- List(1)
       |  } yield a + 1
       |}
       |""".stripMargin
  )

  check(
    "for-comp-map1",
    """|object Main {
       |  val x = List(1).<<m@@ap>>(_ + 1)
       |  val y = for {
       |    a <- List(1)
       |    if true
       |  } yield a + 1
       |}
       |""".stripMargin
  )

  check(
    "for-comp-foreach",
    """|object Main {
       |  val x = List(1).<<for@@each>>(_ => ())
       |  val y = for {
       |    a <- List(1)
       |  } {}
       |}
       |""".stripMargin
  )

  check(
    "for-comp-withFilter",
    """|object Main {
       |  val x = List(1).<<with@@Filter>>(_ => true)
       |  val y = for {
       |    a <- List(1)
       |    if true
       |  } {}
       |}
       |""".stripMargin
  )

  check(
    "for-comp-withFilter1",
    """|object Main {
       |  val x = List(1).withFilter(_ => true).<<m@@ap>>(_ + 1)
       |  val y = for {
       |    a <- List(1)
       |    if true
       |  } yield a + 1
       |}
       |""".stripMargin
  )

  check(
    "for-comp-flatMap1",
    """|object Main {
       |  val x = List(1).<<flat@@Map>>(_ => List(1))
       |  val y = for {
       |    a <- List(1)
       |    b <- List(2)
       |    if true
       |  } yield a + 1
       |}
       |""".stripMargin
  )

  check(
    "for-comp-flatMap2",
    """|object Main {
       |  val x = List(1).withFilter(_ => true).<<flat@@Map>>(_ => List(1))
       |  val y = for {
       |    a <- List(1)
       |    if true
       |    b <- List(2)
       |  } yield a + 1
       |}
       |""".stripMargin
  )

  check(
    "named-arg-backtick",
    """|object Main {
       |  def foo(<<`type`>>: String): String = <<`type`>>
       |  val x = foo(
       |    <<`ty@@pe`>> = "abc"
       |  )
       |}
       |""".stripMargin
  )

  check(
    "map-bind".tag(IgnoreScala3),
    """
      |object Main {
      |  List(1).map {
      |    <<abc>>: Int => <<ab@@c>> + 1
      |  }
      |}""".stripMargin
  )

  check(
    "map-bind1".tag(IgnoreScala3),
    """
      |object Main {
      |  List(1).foldLeft(0){
      |    (<<abc>>: Int, bde: Int) => <<ab@@c>> + bde
      |  }
      |}""".stripMargin
  )

  check(
    "map-bind2".tag(IgnoreScala3),
    """
      |object Main {
      |  List(1).map {
      |    someLongName:@@ Int => someLongName + 1
      |  }
      |}""".stripMargin
  )

  check(
    "map-bind3".tag(IgnoreScala3),
    """
      |object Main {
      |  List(1).map {
      |    someVeryLongName: Int =@@> someVeryLongName + 1
      |  }
      |}""".stripMargin
  )

  check(
    "map-bind4".tag(IgnoreScala3),
    """
      |object Main {
      |  List(1).map {
      |    someLongName: <<I@@nt>> => someLongName + 1
      |  }
      |}""".stripMargin
  )

  check(
    "map-bind5".tag(IgnoreScala3),
    """
      |object Main {
      |  List(1).map {
      |    someLongName: Int => 
      |      val <<ab@@c>> = 2
      |      someLongName + <<abc>>
      |  }
      |}""".stripMargin
  )

  check(
    "map-bind6",
    """
      |object Main {
      |  List("test").map {
      |        case <<string@@Name>>: String if <<stringName>>.startsWith("a") => <<stringName>> + "a"
      |        case stringName: String if stringName.startsWith("b") => stringName + "b"
      |        case stringName: String => stringName + "c"
      |  }
      |}""".stripMargin
  )

  check(
    "extends",
    """
      |abstract class Base(foo: Int, bar: Int)
      |
      |class Test(<<foo>>: Int, bar: Int) extends Base(<<f@@oo>>, bar) {
      |  def transform = <<foo>> + bar
      |  val description = s"$<<foo>> & $bar"
      |}
      |""".stripMargin
  )

  check(
    "extends1",
    """
      |abstract class Base(foo: Int, bar: Int)
      |
      |class Test(<<foo>>: Int, bar: Int) extends Base(<<foo>>, bar) {
      |  def transform = <<fo@@o>> + bar
      |  val description = s"$<<foo>> & $bar"
      |}
      |""".stripMargin
  )

  check(
    "constructor",
    """
      |object Main {
      |  class <<A@@bc>>[T](abc: T)
      |  val x = new <<Abc>>(123)
      |}""".stripMargin
  )

  check(
    "constructor1",
    """
      |object Main {
      |  case class <<Abc>>[T](abc: T)
      |  val x = <<A@@bc>>(123)
      |}""".stripMargin
  )

  check(
    "constructor2",
    """
      |object Main {
      |  class <<A@@bc>>[T](abc: T)
      |  object <<Abc>>
      |  val x = new <<Abc>>(123)
      |}""".stripMargin
  )

  check(
    "constructor3",
    """
      |object Main {
      |  class <<Abc>>[T](abc: T)
      |  object <<Abc>>
      |  val x = new <<A@@bc>>(123)
      |}""".stripMargin
  )

  check(
    "constructor4",
    """
      |object Main {
      |  class <<Abc>>[T](abc: T)
      |  object <<Ab@@c>>
      |  val x = new <<Abc>>(123)
      |}""".stripMargin
  )

  check(
    "constructor5",
    """
      |object Main {
      |  class <<Abc>>[T](abc: T)
      |  object <<Abc>> {
      |    def apply(abc: Int, bde: Int) = new <<Abc>>(abc + bde)
      |  }
      |  val x = <<Ab@@c>>(123, 456)
      |}""".stripMargin
  )

  check(
    "constructor6".tag(IgnoreScala2),
    """
      |class <<Abc>>[T](a: T)
      |object O {
      |  def foo(a: Int) = new <<Abc>>[Int](a)
      |  val x = <<Ab@@c>>[Int](2)
      |}""".stripMargin
  )

  check(
    "constructor7",
    """
      |object Bar {
      |class <<Abc>>[T](a: T)
      |}
      |
      |object O {
      |  val x = new Bar.<<Ab@@c>>(2)
      |}""".stripMargin
  )

  check(
    "constructor8".tag(IgnoreScala2),
    """
      |object Bar {
      |class <<Abc>>[T](a: T)
      |}
      |
      |object O {
      |  val x = Bar.<<Ab@@c>>[Int](2)
      |}""".stripMargin
  )

  check(
    "self-type",
    """|trait Foo {
       |  self: <<An@@y>> =>
       |    def bar(): <<Any>>
       |}
       |""".stripMargin
  )

  check(
    "self-type2",
    """|trait Foo {
       |  self: <<Any>> =>
       |    def bar(): <<An@@y>>
       |}
       |""".stripMargin
  )

}
