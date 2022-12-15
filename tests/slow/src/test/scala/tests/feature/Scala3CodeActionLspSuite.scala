package tests.feature

import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.codeactions.ConvertToNamedArguments
import scala.meta.internal.metals.codeactions.CreateCompanionObjectCodeAction
import scala.meta.internal.metals.codeactions.ExtractMethodCodeAction
import scala.meta.internal.metals.codeactions.ExtractRenameMember
import scala.meta.internal.metals.codeactions.ExtractValueCodeAction
import scala.meta.internal.metals.codeactions.FlatMapToForComprehensionCodeAction
import scala.meta.internal.metals.codeactions.ImplementAbstractMembers
import scala.meta.internal.metals.codeactions.InsertInferredType
import scala.meta.internal.metals.codeactions.RewriteBracesParensCodeAction
import scala.meta.internal.metals.codeactions.SourceOrganizeImports
import scala.meta.internal.mtags.MtagsEnrichments.XtensionAbsolutePath

import munit.Location
import munit.TestOptions
import tests.codeactions.BaseCodeActionLspSuite

class Scala3CodeActionLspSuite
    extends BaseCodeActionLspSuite("cross-code-actions") {

  override protected val scalaVersion: String = BuildInfo.scala3

  checkNoAction(
    "val",
    """|package a
       |
       |object A {
       |  val al<<>>pha: Int = 123
       |}
       |""".stripMargin,
  )

  check(
    "for-comprehension-using",
    """object A{
      | class Context
      | val ctx = new Context
      | def hello(using ctx: Context)(i: Int): Int = i
      | val res = List(1,2,3).ma<<>>p(hello(using ctx)(_)).isEmpty
      |}""".stripMargin,
    s"""${RewriteBracesParensCodeAction.toBraces("map")}
       |${FlatMapToForComprehensionCodeAction.flatMapToForComprehension}""".stripMargin,
    """|object A{
       | class Context
       | val ctx = new Context
       | def hello(using ctx: Context)(i: Int): Int = i
       | val res = {
       |   for {
       |     generatedByMetals <- List(1, 2, 3)
       |   } yield {
       |     hello(using ctx)(generatedByMetals)
       |   }
       | }.isEmpty
       |}
       |""".stripMargin,
    1,
  )

  check(
    "organize-imports",
    """
      |package a
      |import scala.concurrent.{ExecutionContext, Future}
      |import scala.util.Try<<>>
      |
      |object A {
      |  val executionContext: ExecutionContext = ???
      |  val k = Future.successful(1)
      |  val tr = Try{ new Exception("name") }
      |}
      |""".stripMargin,
    s"${SourceOrganizeImports.title}",
    """|package a
       |import scala.concurrent.ExecutionContext
       |import scala.concurrent.Future
       |import scala.util.Try
       |
       |object A {
       |  val executionContext: ExecutionContext = ???
       |  val k = Future.successful(1)
       |  val tr = Try{ new Exception("name") }
       |}
       |""".stripMargin,
    kind = List(SourceOrganizeImports.kind),
  )

  checkExtractedMember(
    "extract-enum",
    """|package a
       |
       |case class A()
       |
       |enum <<Color>>(val rgb: Int):
       |   case Red   extends Color(0xFF0000)
       |   case Green extends Color(0x00FF00)
       |   case Blue  extends Color(0x0000FF)
       |end Color
       |""".stripMargin,
    s"""|${ExtractRenameMember.title("enum", "Color")}""".stripMargin,
    """|package a
       |
       |case class A()
       |
       |""".stripMargin,
    newFile = (
      "Color.scala",
      s"""|package a
          |
          |enum Color(val rgb: Int):
          |   case Red   extends Color(0xFF0000)
          |   case Green extends Color(0x00FF00)
          |   case Blue  extends Color(0x0000FF)
          |end Color
          |""".stripMargin,
    ),
  )

  check(
    "val-pattern",
    """|package a
       |
       |object A:
       |  val (fir<<>>st, second) = (List(1), List(""))
       |""".stripMargin,
    s"""|${InsertInferredType.insertTypeToPattern}
        |""".stripMargin,
    """|package a
       |
       |object A:
       |  val (first: List[Int], second) = (List(1), List(""))
       |""".stripMargin,
  )

  check(
    "auto-import",
    """|package a
       |
       |object A:
       |  var al<<>>pha = List(123).toBuffer
       |
       |""".stripMargin,
    s"""|${InsertInferredType.insertType}
        |""".stripMargin,
    """|package a
       |
       |import scala.collection.mutable.Buffer
       |
       |object A:
       |  var alpha: Buffer[Int] = List(123).toBuffer
       |""".stripMargin,
  )

  check(
    "single-def",
    """|object Main {
       |  def method2(i: Int) = ???
       |  def main = {
       |    def inner(i : Int) = method2(i + 23 + <<123>>)
       |  }
       |}
       |""".stripMargin,
    s"""|${ExtractValueCodeAction.title("i + 23 + 1(...)")}
        |${ConvertToNamedArguments.title("method2(...)")}
        |""".stripMargin,
    """|object Main {
       |  def method2(i: Int) = ???
       |  def main = {
       |    def inner(i : Int) = {
       |      val newValue = i + 23 + 123
       |      method2(newValue)
       |    }
       |  }
       |}
       |""".stripMargin,
  )

  check(
    "single-def-optional",
    """|object Main:
       |  def method2(i: Int) = ???
       |  def main =
       |    def inner(i : Int) = method2(i + 23 + <<123>>)
       |
       |""".stripMargin,
    s"""|${ExtractValueCodeAction.title("i + 23 + 1(...)")}
        |${ConvertToNamedArguments.title("method2(...)")}
        |""".stripMargin,
    """|object Main:
       |  def method2(i: Int) = ???
       |  def main =
       |    def inner(i : Int) =
       |      val newValue = i + 23 + 123
       |      method2(newValue)
       |""".stripMargin,
  )

  check(
    "single-def-split",
    """|object Main {
       |  def method2(i: Int) = ???
       |  
       |  def main(i : Int) =
       |    method2(i + 23 + <<123>>)
       |}
       |""".stripMargin,
    s"""|${ExtractValueCodeAction.title("i + 23 + 1(...)")}
        |${ConvertToNamedArguments.title("method2(...)")}
        |""".stripMargin,
    """|object Main {
       |  def method2(i: Int) = ???
       |  
       |  def main(i : Int) = {
       |    val newValue = i + 23 + 123
       |    method2(newValue)
       |  }
       |}
       |""".stripMargin,
  )

  check(
    "single-def-split-optional",
    """|object Main:
       |  def method2(i: Int) = ???
       |  
       |  def main(i : Int) =
       |  method2(i + 23 + <<123>>)
       |
       |""".stripMargin,
    s"""|${ExtractValueCodeAction.title("i + 23 + 1(...)")}
        |${ConvertToNamedArguments.title("method2(...)")}
        |""".stripMargin,
    """|object Main:
       |  def method2(i: Int) = ???
       |  
       |  def main(i : Int) =
       |    val newValue = i + 23 + 123
       |    method2(newValue)
       |
       |""".stripMargin,
  )

  check(
    "single-toplevel-optional",
    """|
       |def method2(i: Int) = {
       |  val a = 1
       |  a + 2
       |}
       |  
       |def main(i : Int) = method2(i + 23 + <<123>>)
       |
       |""".stripMargin,
    s"""|${ExtractValueCodeAction.title("i + 23 + 1(...)")}
        |${ConvertToNamedArguments.title("method2(...)")}
        |""".stripMargin,
    """|def method2(i: Int) = {
       |  val a = 1
       |  a + 2
       |}
       |  
       |def main(i : Int) = {
       |  val newValue = i + 23 + 123
       |  method2(newValue)
       |}
       |""".stripMargin,
  )

  check(
    "insert-companion-object-of-braceless-enum-inside-parent-object",
    """|object Baz:
       |  enum F<<>>oo:
       |    case a
       |    def fooMethod(): Unit = {
       |      val a = 3
       |    }
       |  end Foo
       |
       |  class Bar {}
       |""".stripMargin,
    s"""|${CreateCompanionObjectCodeAction.companionObjectCreation("Foo")}
        |""".stripMargin,
    """|object Baz:
       |  enum Foo:
       |    case a
       |    def fooMethod(): Unit = {
       |      val a = 3
       |    }
       |  end Foo
       |
       |  object Foo:
       |    ???
       |
       |  class Bar {}
       |""".stripMargin,
  )

  check(
    "insert-companion-object-of-braceless-case-class-file-end",
    """|case class F<<>>oo(a: Int):
       |  def b = a""".stripMargin,
    s"""|${CreateCompanionObjectCodeAction.companionObjectCreation("Foo")}
        |""".stripMargin,
    """|case class Foo(a: Int):
       |  def b = a
       |
       |object Foo:
       |  ???
       |
       |""".stripMargin,
    fileName = "Foo.scala",
  )

  check(
    // it should be a syntax error
    "implement-all-braceless-noaction",
    """|package a
       |
       |object A:
       |  trait Base:
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |  class <<Concrete>> extends Base:
       |""".stripMargin,
    "",
    """|package a
       |
       |object A:
       |  trait Base:
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |  class Concrete extends Base:
       |""".stripMargin,
    expectError = true,
    expectNoDiagnostics = false,
  )

  check(
    "named-basic",
    """|object Something {
       |  case class Foo(param1: Int, param2: Int, param3: Int)
       |  Foo(<<1>>, 2, param3 = 3)
       |  Foo(4,5,6)
       |}""".stripMargin,
    s"""|${ExtractValueCodeAction.title("1")}
        |${ConvertToNamedArguments.title("Foo(...)")}""".stripMargin,
    """|object Something {
       |  case class Foo(param1: Int, param2: Int, param3: Int)
       |  Foo(param1 = 1, param2 = 2, param3 = 3)
       |  Foo(4,5,6)
       |}""".stripMargin,
    selectedActionIndex = 1,
  )

  check(
    "convert-new-apply-multiple",
    """|object Something {
       |  class Foo(param1: Int, param2: Int)(param3: Int)
       |  val a = new Foo(1, param2 = 2<<)>>(3)
       |}""".stripMargin,
    s"${ConvertToNamedArguments.title("Foo(...)")}",
    """|object Something {
       |  class Foo(param1: Int, param2: Int)(param3: Int)
       |  val a = new Foo(param1 = 1, param2 = 2)(param3 = 3)
       |}""".stripMargin,
  )
  check(
    "new-apply-multiple-type",
    """|object Something {
       |  class Foo[T](param1: Int, param2: Int)(param3: T)
       |  val a = new Foo[Int]<<(>>1, param2 = 2)(3)
       |}""".stripMargin,
    s"${ConvertToNamedArguments.title("Foo[Int](...)")}",
    """|object Something {
       |  class Foo[T](param1: Int, param2: Int)(param3: T)
       |  val a = new Foo[Int](param1 = 1, param2 = 2)(param3 = 3)
       |}""".stripMargin,
  )
  check(
    "given-object-creation",
    """|package a
       |
       |trait Foo:
       |  def foo(x: Int): Int
       |  def bar(x: String): String
       |
       |given <<Foo>> with {}
       |""".stripMargin,
    s"""|${ImplementAbstractMembers.title}
        |""".stripMargin,
    """|package a
       |
       |trait Foo:
       |  def foo(x: Int): Int
       |  def bar(x: String): String
       |
       |given Foo with {
       |
       |  override def foo(x: Int): Int = ???
       |
       |  override def bar(x: String): String = ???
       |
       |}
       |""".stripMargin,
  )

  check(
    "given-object-with",
    """|package given
       |
       |trait Foo:
       |  def foo(x: Int): Int
       |  def bar(x: String): String
       |
       |given <<Foo>>
       |""".stripMargin,
    s"""|${ImplementAbstractMembers.title}
        |""".stripMargin,
    """|package given
       |
       |trait Foo:
       |  def foo(x: Int): Int
       |  def bar(x: String): String
       |
       |given Foo with
       |
       |  override def foo(x: Int): Int = ???
       |
       |  override def bar(x: String): String = ???
       |""".stripMargin,
    expectNoDiagnostics = false,
  )

  check(
    "multi-param",
    s"""|object A{
        |  val b = 4
        |  val c = 3
        |  def method(i: Int, j: Int) = i + 1
        |  val a = { 
        |    val c = 5
        |    <<123 + method(c, b) + method(b,c)>>
        |  }
        |}""".stripMargin,
    s"""|${ExtractMethodCodeAction.title("val `a`")}
        |${ExtractMethodCodeAction.title("object `A`")}
        |""".stripMargin,
    s"""|object A{
        |  val b = 4
        |  val c = 3
        |  def method(i: Int, j: Int) = i + 1
        |  def newMethod(c: Int): Int =
        |    123 + method(c, b) + method(b,c)
        |
        |  val a = { 
        |    val c = 5
        |    newMethod(c)
        |  }
        |}""".stripMargin,
    selectedActionIndex = 1,
  )

  check(
    "higher-scope",
    s"""|object A{
        |  val b = 4
        |  def method(i: Int, j: Int, k: Int) = i + j + k
        |  val a = {
        |    def f() = {
        |      val c = 1
        |      <<val d = 3
        |      method(d, b, c)>>
        |    }
        |  }
        |}""".stripMargin,
    s"""|${ExtractMethodCodeAction.title("method `f`")}
        |${ExtractMethodCodeAction.title("val `a`")}
        |${ExtractMethodCodeAction.title("object `A`")}
        |""".stripMargin,
    s"""|object A{
        |  val b = 4
        |  def method(i: Int, j: Int, k: Int) = i + j + k
        |  val a = {
        |    def newMethod(c: Int): Int =
        |      val d = 3
        |      method(d, b, c)
        |
        |    def f() = {
        |      val c = 1
        |      newMethod(c)
        |    }
        |  }
        |}""".stripMargin,
    selectedActionIndex = 1,
  )

  def checkExtractedMember(
      name: TestOptions,
      input: String,
      expectedActions: String,
      expectedCode: String,
      newFile: (String, String),
      selectedActionIndex: Int = 0,
  )(implicit loc: Location): Unit = {
    check(
      name,
      input,
      expectedActions,
      expectedCode,
      selectedActionIndex,
      extraOperations = {
        val (fileName, content) = newFile
        val absolutePath = workspace.resolve(getPath(fileName))
        assert(
          absolutePath.exists,
          s"File $absolutePath should have been created",
        )
        assertNoDiff(absolutePath.readText, content)
      },
    )
  }

  private def getPath(name: String) = s"a/src/main/scala/a/$name"

}
