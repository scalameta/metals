package tests.pc

import org.eclipse.{lsp4j => l}

import tests.BaseCodeActionSuite
import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.metals.TextEdits
import scala.meta.internal.jdk.CollectionConverters._

class AutoImplementAbstractMembersSuite extends BaseCodeActionSuite {

  checkEdit(
    "classdef",
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |  }
       |  class <<Concrete>> extends Base {
       |  }
       |}
       |""".stripMargin,
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |  }
       |  class Concrete extends Base {
       |
       |    override def foo(x: Int): Int = ???
       |
       |    override def bar(x: String): String = ???
       |
       |  }
       |}
       |""".stripMargin
  )

  checkEdit(
    "empty-lines-between-members",
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |  }
       |  class <<Concrete>> extends Base {
       |
       |    def bar(x: String): String = ???
       |
       |  }
       |}
       |""".stripMargin,
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |  }
       |  class Concrete extends Base {
       |
       |    override def foo(x: Int): Int = ???
       |
       |
       |    def bar(x: String): String = ???
       |
       |  }
       |}
       |""".stripMargin
  )

  checkEdit(
    "objectdef",
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |  }
       |  object <<Concrete>> extends Base {
       |  }
       |}
       |""".stripMargin,
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |  }
       |  object Concrete extends Base {
       |
       |    override def foo(x: Int): Int = ???
       |
       |  }
       |}
       |""".stripMargin
  )

  checkEdit(
    "overload",
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |  }
       |  class <<Concrete>> extends Base {
       |    override def foo(x: Int): Int = x
       |  }
       |}
       |""".stripMargin,
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |  }
       |  class Concrete extends Base {
       |
       |    override def bar(x: String): String = ???
       |
       |    override def foo(x: Int): Int = x
       |  }
       |}
       |""".stripMargin
  )

  checkEdit(
    "braces",
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |  }
       |  class <<Concrete>> extends Base {}
       |}
       |""".stripMargin,
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |  }
       |  class Concrete extends Base {
       |
       |    override def foo(x: Int): Int = ???
       |
       |    override def bar(x: String): String = ???
       |
       |  }
       |}
       |""".stripMargin
  )

  checkEdit(
    "object-creation",
    """
      |object Main {
      |  new <<Iterable>>[Int] {}
      |}
    """.stripMargin,
    """
      |object Main {
      |  new Iterable[Int] {
      |
      |    override def iterator: Iterator[Int] = ???
      |
      |  }
      |}
      |""".stripMargin
  )

  checkEdit(
    "context-bound",
    """
      |trait Context {
      |   def add[T:Ordering]: T
      |}
      |object Main {
      |  new <<Context>> {
      |  }
      |}
     """.stripMargin,
    """
      |trait Context {
      |   def add[T:Ordering]: T
      |}
      |object Main {
      |  new Context {
      |
      |    override def add[T: Ordering]: T = ???
      |
      |  }
      |}
      |""".stripMargin
  )

  checkEdit(
    "generics-inheritance",
    """
      |trait Context[T] {
      |   def method: T
      |}
      |object Main {
      |  class <<Concrete>> extends Context[Int] {
      |  }
      |}
     """.stripMargin,
    """
      |trait Context[T] {
      |   def method: T
      |}
      |object Main {
      |  class Concrete extends Context[Int] {
      |
      |    override def method: Int = ???
      |
      |  }
      |}
      |""".stripMargin
  )

  checkEdit(
    "ignore-non-abstract",
    """
      |trait Abstract extends SuperAbstract {
      |  def aaa: Int
      |  def bbb: Int = 2 // should be ignored
      |  type TypeAlias = String // should be ignored
      |}
      |object Main {
      |  new <<Abstract>> {
      |  }
      |}
     """.stripMargin,
    """
      |trait Abstract extends SuperAbstract {
      |  def aaa: Int
      |  def bbb: Int = 2 // should be ignored
      |  type TypeAlias = String // should be ignored
      |}
      |object Main {
      |  new Abstract {
      |
      |    override def aaa: Int = ???
      |
      |  }
      |}
      |""".stripMargin
  )

  checkEdit(
    "import",
    """|abstract class Mutable {
       |  def foo: scala.collection.mutable.Set[Int]
       |  def bar: scala.collection.immutable.Set[Int]
       |}
       |object Main {
       |  new <<Mutable>> {
       |  }
       |}
       |""".stripMargin,
    """|import scala.collection.mutable
       |abstract class Mutable {
       |  def foo: scala.collection.mutable.Set[Int]
       |  def bar: scala.collection.immutable.Set[Int]
       |}
       |object Main {
       |  new Mutable {
       |
       |    override def foo: mutable.Set[Int] = ???
       |
       |    override def bar: Set[Int] = ???
       |
       |  }
       |}
       |""".stripMargin
  )

  checkEdit(
    "nested-inheritance",
    """|abstract class SuperAbstract {
       |  def foo: Int
       |}
       |trait Bar extends SuperAbstract {
       |  def bar: Int
       |}
       |object Main {
       |  class <<Baz>> extends Bar {
       |  }
       |}
       |""".stripMargin,
    """|abstract class SuperAbstract {
       |  def foo: Int
       |}
       |trait Bar extends SuperAbstract {
       |  def bar: Int
       |}
       |object Main {
       |  class Baz extends Bar {
       |
       |    override def foo: Int = ???
       |
       |    override def bar: Int = ???
       |
       |  }
       |}
       |""".stripMargin
  )

  checkEdit(
    "jutil",
    """|abstract class JUtil {
       |  def foo: java.util.List[Int]
       |}
       |class <<Main>> extends JUtil {
       |}
       |""".stripMargin,
    """|import java.{util => ju}
       |abstract class JUtil {
       |  def foo: java.util.List[Int]
       |}
       |class Main extends JUtil {
       |
       |  override def foo: ju.List[Int] = ???
       |
       |}
       |""".stripMargin
  )

  checkEdit(
    "jutil-conflict",
    """|package jutil
       |abstract class JUtil {
       |  def foo: java.util.List[Int]
       |}
       |class <<Main>> extends JUtil {
       |  val java = 42
       |}
       |""".stripMargin,
    // Ensure we don't insert `_root_` prefix for import because `val java = 42` is local.
    """|package jutil
       |
       |import java.{util => ju}
       |abstract class JUtil {
       |  def foo: java.util.List[Int]
       |}
       |class Main extends JUtil {
       |
       |  override def foo: ju.List[Int] = ???
       |
       |  val java = 42
       |}
       |""".stripMargin
  )

  checkEdit(
    "val",
    """|abstract class Abstract {
       |  val baz: String
       |}
       |class <<Main>> extends Abstract {
       |}
       |""".stripMargin,
    """|abstract class Abstract {
       |  val baz: String
       |}
       |class Main extends Abstract {
       |
       |  override val baz: String = ???
       |
       |}
       |""".stripMargin
  )

  checkEdit(
    "indent-def",
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |  }
       |  // if there's a member, infer indent based on it.
       |  class <<Concrete>> extends Base {
       |       override def foo(x: Int): Int = x
       |  }
       |}
       |""".stripMargin,
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |  }
       |  // if there's a member, infer indent based on it.
       |  class Concrete extends Base {
       |
       |       override def bar(x: String): String = ???
       |
       |       override def foo(x: Int): Int = x
       |  }
       |}
       |""".stripMargin
  )

  checkEdit(
    "indent-val",
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |  }
       |  // if there's a member, infer indent based on it.
       |  class <<Concrete>> extends Base {
       |           val test = 1
       |  }
       |}
       |""".stripMargin,
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |  }
       |  // if there's a member, infer indent based on it.
       |  class Concrete extends Base {
       |
       |           override def foo(x: Int): Int = ???
       |
       |           override def bar(x: String): String = ???
       |
       |           val test = 1
       |  }
       |}
       |""".stripMargin
  )

  checkEdit(
    "indent-type",
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |  }
       |  // if there's a member, infer indent based on it.
       |  class <<Concrete>> extends Base {
       |           type T = Int
       |  }
       |}
       |""".stripMargin,
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |  }
       |  // if there's a member, infer indent based on it.
       |  class Concrete extends Base {
       |
       |           override def foo(x: Int): Int = ???
       |
       |           override def bar(x: String): String = ???
       |
       |           type T = Int
       |  }
       |}
       |""".stripMargin
  )

  checkEdit(
    "indent-object-creation",
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |  }
       |  new <<Base>> {
       |          def bar(x: String): Int = x
       |  }
       |}
       |""".stripMargin,
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |  }
       |  new Base {
       |
       |          override def foo(x: Int): Int = ???
       |
       |          def bar(x: String): Int = x
       |  }
       |}
       |""".stripMargin
  )

  checkEdit(
    "infer-indent-constructor",
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |  }
       |  <<class>> Concrete(num: Int) extends Base {
       |  }
       |}
       |""".stripMargin,
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |  }
       |  class Concrete(num: Int) extends Base {
       |
       |    override def foo(x: Int): Int = ???
       |
       |    override def bar(x: String): String = ???
       |
       |  }
       |}
       |""".stripMargin
  )

  checkEdit(
    "infer-indent-auxiliary-constructor",
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |  }
       |  <<class>> Concrete(num: Int) extends Base {
       |      def this() = { this(4) }
       |  }
       |}
       |""".stripMargin,
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |  }
       |  class Concrete(num: Int) extends Base {
       |
       |      override def foo(x: Int): Int = ???
       |
       |      override def bar(x: String): String = ???
       |
       |      def this() = { this(4) }
       |  }
       |}
       |""".stripMargin
  )

  checkEdit(
    "indent-closing-brace",
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |  }
       |      new <<Base>> {}
       |}
       |""".stripMargin,
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |  }
       |      new Base {
       |
       |        override def foo(x: Int): Int = ???
       |
       |      }
       |}
       |""".stripMargin
  )

  checkEdit(
    "complete-braces-moduledef",
    """|package a
       |
       |trait Base {
       |  def foo(x: Int): Int
       |}
       |object <<Concrete>> extends Base
       |""".stripMargin,
    """|package a
       |
       |trait Base {
       |  def foo(x: Int): Int
       |}
       |object Concrete extends Base {
       |
       |  override def foo(x: Int): Int = ???
       |
       |}
       |""".stripMargin
  )

  checkEdit(
    "complete-braces-indent",
    """|package a
       |
       |trait Base {
       |  def foo(x: Int): Int
       |}
       |object Test {
       |   class <<Concrete>> extends Base
       |}
       |""".stripMargin,
    """|package a
       |
       |trait Base {
       |  def foo(x: Int): Int
       |}
       |object Test {
       |   class Concrete extends Base {
       |
       |     override def foo(x: Int): Int = ???
       |
       |   }
       |}
       |""".stripMargin
  )

  def checkEdit(
      name: String,
      original: String,
      expected: String
  ): Unit =
    test(name) {
      val edits = getAutoImplement(original)
      if (edits.isEmpty) fail("obtained no edits")
      val (code, _, _) = params(original)
      val obtained = TextEdits.applyEdits(code, edits)
      assertNoDiff(obtained, expected)
    }

  def getAutoImplement(
      original: String,
      filename: String = "A.scala"
  ): List[l.TextEdit] = {
    val (code, _, offset) = params(original)
    val result = pc
      .implementAbstractMembers(
        CompilerOffsetParams("file:/" + filename, code, offset, cancelToken)
      )
      .get()
    result.asScala.toList
  }

}
