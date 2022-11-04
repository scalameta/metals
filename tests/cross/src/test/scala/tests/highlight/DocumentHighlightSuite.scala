package tests.highlight

import tests.BaseDocumentHighlightSuite

class DocumentHighlightSuite extends BaseDocumentHighlightSuite {

  check(
    "single",
    """
      |object Main {
      |  Option(1).<<he@@ad>>
      |}""".stripMargin,
  )

  check(
    "multiple",
    """
      |object Main {
      |  val <<abc>> = 123
      |  <<abc>>.toInt
      |  println(<<ab@@c>>)
      |}""".stripMargin,
  )
  check(
    "multiple2",
    """
      |object Main {
      |  val <<a@@bc>> = 123
      |  <<abc>>.toInt
      |  println(<<abc>>)
      |}""".stripMargin,
  )

  check(
    "multiple3",
    """
      |object Main {
      |  val <<abc>> = 123
      |  <<ab@@c>>.toInt
      |  println(<<abc>>)
      |}""".stripMargin,
  )

  check(
    "different-symbols",
    """
      |object Main {
      |  val abc = 123
      |  abc.<<to@@Int>>
      |  134l.toInt
      |}""".stripMargin,
  )

  check(
    "scopes",
    """
      |object Main {
      |  val <<@@a>> = 123
      |  val f = (a: Int) => a + 1
      |  println(<<a>>)
      |}""".stripMargin,
  )

  check(
    "scopes2",
    """
      |object Main {
      |  val <<a>> = 123
      |  val f = (a: Int) => a + 1
      |  println(<<@@a>>)
      |}""".stripMargin,
  )

  check(
    "params",
    """
      |case class User(<<n@@ame>>: String)
      |object Main {
      |  val user = User(<<name>> = "Susan")
      |  println(user.<<name>>)
      |  user.copy(<<name>> = "John")
      |}""".stripMargin,
  )

  check(
    "params2",
    """
      |case class User(<<name>>: String)
      |object Main {
      |  val user = User(<<na@@me>> = "Susan")
      |  println(user.<<name>>)
      |  user.copy(<<name>> = "John")
      |}""".stripMargin,
  )

  check(
    "params3",
    """
      |case class User(<<name>>: String)
      |object Main {
      |  val user = User(<<name>> = "Susan")
      |  println(user.<<n@@ame>>)
      |  user.copy(<<name>> = "John")
      |}""".stripMargin,
  )

  check(
    "params4",
    """
      |case class User(<<name>>: String)
      |object Main {
      |  val user = User(<<name>> = "Susan")
      |  println(user.<<name>>)
      |  user.copy(<<na@@me>> = "John")
      |}""".stripMargin,
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
      |}""".stripMargin,
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
      |}""".stripMargin,
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
      |}""".stripMargin,
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
      |}""".stripMargin,
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
      |}""".stripMargin,
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
      |}""".stripMargin,
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
      |}""".stripMargin,
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
      |}""".stripMargin,
  )

  check(
    "var",
    """
      |object Main {
      |  var <<ab@@d>> = 123
      |  <<abd>> = 344
      |  <<abd>> +=1
      |  println(<<abd>>)
      |}""".stripMargin,
  )

  check(
    "var2",
    """
      |object Main {
      |  var <<abd>> = 123
      |  <<ab@@d>> = 344
      |  <<abd>> +=1
      |  println(<<abd>>)
      |}""".stripMargin,
  )

  check(
    "var3",
    """
      |object Main {
      |  var <<abd>> = 123
      |  <<abd>> = 344
      |  <<ab@@d>> +=1
      |  println(<<abd>>)
      |}""".stripMargin,
  )

  check(
    "var4",
    """
      |object Main {
      |  var <<abd>> = 123
      |  <<abd>> = 344
      |  <<abd>> +=1
      |  println(<<a@@bd>>)
      |}""".stripMargin,
  )

  check(
    "overloaded",
    """
      |object Main {
      |  def hello() = ""
      |  def <<hel@@lo>>(a : Int) = ""
      |  def hello(a : Int, b : String) = ""
      |}""".stripMargin,
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
      |}""".stripMargin,
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
      |}""".stripMargin,
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
      |}""".stripMargin,
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
      |}""".stripMargin,
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
      |}""".stripMargin,
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
      |}""".stripMargin,
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
      |}""".stripMargin,
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
      |}""".stripMargin,
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
      |}""".stripMargin,
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
      |}""".stripMargin,
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
      |}""".stripMargin,
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
      |}""".stripMargin,
  )

  check(
    "same-name",
    """
      |object Test {
      |  def foo(name: String) = ???
      |  def bar(<<n@@ame>>: String) = ???
      |  foo(name = "123")
      |  bar(<<name>> = "123")
      |}""".stripMargin,
  )

  check(
    "same-name2",
    """
      |object Test {
      |  def foo(name: String) = ???
      |  def bar(<<name>>: String) = ???
      |  foo(name = "123")
      |  bar(<<na@@me>> = "123")
      |}""".stripMargin,
  )

  check(
    "same-name3",
    """
      |object Test {
      |  def foo(<<na@@me>>: String) = ???
      |  def bar(name: String) = ???
      |  foo(<<name>> = "123")
      |  bar(name = "123")
      |}""".stripMargin,
  )

  check(
    "same-name4",
    """
      |object Test {
      |  def foo(<<name>>: String) = ???
      |  def bar(name: String) = ???
      |  foo(<<na@@me>> = "123")
      |  bar(name = "123")
      |}""".stripMargin,
  )

  check(
    "import1",
    """
      |import scala.util.<<Tr@@y>>
      |object Test {
      |   <<Try>>(1)
      |}""".stripMargin,
  )

  check(
    "import2",
    """
      |import scala.util.<<Try>>
      |object Test {
      |   <<Tr@@y>>(1)
      |}""".stripMargin,
  )

  check(
    "import3",
    """
      |import scala.<<ut@@il>>.Try
      |object Test {
      |   scala.<<util>>.Try(1)
      |}""".stripMargin,
  )

  check(
    "import4",
    """
      |import scala.<<util>>.Try
      |object Test {
      |   scala.<<ut@@il>>.Try(1)
      |}""".stripMargin,
  )

  check(
    "rename1",
    """
      |import scala.util.{ <<Try>> => <<ATr@@y>>}
      |object Test {
      |   <<ATry>>(1)
      |}""".stripMargin,
  )

  check(
    "rename2",
    """
      |import scala.util.{ <<Try>> => <<ATry>>}
      |object Test {
      |   <<ATr@@y>>(1)
      |}""".stripMargin,
  )

  // @note, we could try and not highlight normal Try,
  // but this might still be useful
  check(
    "rename3",
    """
      |import scala.util.{ <<Try>> => <<ATr@@y>>}
      |object Test {
      |   scala.util.<<Try>>(1)
      |}""".stripMargin,
  )

  check(
    "rename4",
    """
      |import scala.util.{ <<Try>> => <<ATry>>}
      |object Test {
      |   scala.util.<<Tr@@y>>(1)
      |}""".stripMargin,
  )

  check(
    "rename5",
    """
      |import scala.util.{ <<T@@ry>> => <<ATry>>}
      |object Test {
      |   scala.util.<<Try>>(1)
      |}""".stripMargin,
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
      |}""".stripMargin,
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
      |}""".stripMargin,
  )

  check(
    "inner-class1",
    """|object Main {
       |  def foo = {
       |    case class <<U@@ser>>(name: String)
       |    object <<User>>{ def nnn = ""}
       |    <<User>>.nnn
       |  }
       |}""".stripMargin,
  )

  check(
    "inner-class2",
    """|object Main {
       |  def foo = {
       |    case class <<User>>(name: String)
       |    object <<U@@ser>>{ def nnn = ""}
       |    <<User>>.nnn
       |  }
       |}""".stripMargin,
  )

  check(
    "inner-class3",
    """|object Main {
       |  def foo = {
       |    case class <<User>>(name: String)
       |    object <<User>>{ def nnn = ""}
       |    <<Use@@r>>.nnn
       |  }
       |}""".stripMargin,
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
       |}""".stripMargin,
  )

  check(
    // Scala 2.12.x has a bug where the namePos points at `object`
    // working around would it involve a lot of additional logic
    "package-object".tag(IgnoreScala212),
    """|package example
       |
       |package object <<nes@@ted>> {
       |
       |  class PackageObjectNestedClass
       |
       |}
       |""".stripMargin,
  )

  check(
    "named-param",
    """|object Main {
       |  def foo = {
       |      case class User(<<name>>: String)
       |      val a = User(<<na@@me>> = "abc")
       |  }
       |}""".stripMargin,
  )

  check(
    "backtick",
    """|object Main {
       |  val <<`hi-!`>> = 5
       |
       |  <<`hi@@-!`>> + 3
       |}""".stripMargin,
  )

}
